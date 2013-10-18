package scala.pickling

import scala.pickling.internal._
import scala.reflect.runtime.{universe => ru}
import ir._

// purpose of this macro: implementation of genPickler[T]. i.e. the macro that is selected
// via implicit search and which initiates the process of generating a pickler for a given type T
// NOTE: dispatch is done elsewhere. picklers generated by genPickler[T] only know how to process T
// but not its subclasses or the types convertible to it!
trait PicklerMacros extends Macro {
  import c.universe._

  def impl[T: c.WeakTypeTag](format: c.Tree): c.Tree = preferringAlternativeImplicits {
    import definitions._
    val tpe = weakTypeOf[T]
    val sym = tpe.typeSymbol.asClass
    import irs._

    val primitiveSizes = Map(
      typeOf[Int] -> 4,
      typeOf[Short] -> 2,
      typeOf[Long] -> 8,
      typeOf[Double] -> 8,
      typeOf[Byte] -> 1,
      typeOf[Char] -> 2,
      typeOf[Float] -> 4,
      typeOf[Double] -> 8,
      typeOf[Boolean] -> 1
    )

    def getField(fir: FieldIR): Tree =
      if (fir.isPublic) q"picklee.${TermName(fir.name)}"
      else reflectively("picklee", fir)(fm => q"$fm.get.asInstanceOf[${fir.tpe}]").head //TODO: don't think it's possible for this to return an empty list, so head should be OK

    // this exists so as to provide as much information as possible about the size of the object
    // to-be-pickled to the picklers at runtime. In the case of the binary format for example,
    // this allows us to remove array copying and allocation bottlenecks
    // Note: this takes a "flattened" ClassIR
    // returns a tree with the size and a list of trees that have to be checked for null
    def computeKnownSizeIfPossible(cir: ClassIR): (Option[Tree], List[Tree]) = {
      if (cir.tpe <:< typeOf[Array[_]]) {
        val TypeRef(_, _, List(elTpe)) = cir.tpe
        val knownSize =
          if (elTpe.isEffectivelyPrimitive) Some(q"picklee.length * ${primitiveSizes(elTpe)} + 4")
          else None
        knownSize -> Nil
      } else {
        val possibleSizes: List[(Option[Tree], Option[Tree])] = cir.fields map {
          case fld if fld.tpe.isEffectivelyPrimitive =>
            val isScalar = !(fld.tpe <:< typeOf[Array[_]])
            if (isScalar) None -> Some(q"${primitiveSizes(fld.tpe)}")
            else {
              val TypeRef(_, _, List(elTpe)) = fld.tpe
              Some(getField(fld)) -> Some(q"${getField(fld)}.length * ${primitiveSizes(elTpe)} + 4")
            }
          case _ =>
            None -> None
        }

        val possibleSizes1 = possibleSizes.map(_._2)
        val resOpt =
          if (possibleSizes1.contains(None) || possibleSizes1.isEmpty) None
          else Some(possibleSizes1.map(_.get).reduce((t1, t2) => q"$t1 + $t2"))
        val resLst = possibleSizes.flatMap(p => if (p._1.isEmpty) List() else List(p._1.get))
        (resOpt, resLst)
      }
    }
    def unifiedPickle = { // NOTE: unified = the same code works for both primitives and objects
      val cir = flattenedClassIR(tpe)

      val hintKnownSize = computeKnownSizeIfPossible(cir) match {
        case (None, lst) => q""
        case (Some(tree), lst) =>
          val typeNameLen = tpe.key.getBytes("UTF-8").length
          val noNullTree  = lst.foldLeft[Tree](Literal(Constant(true)))((acc, curr) => q"$acc && ($curr != null)")
          q"""
            if ($noNullTree) {
              val size = $tree + $typeNameLen + 4
              builder.hintKnownSize(size)
            }
          """
      }
      val beginEntry = q"""
        $hintKnownSize
        builder.beginEntry(picklee)
      """
      val (nonLoopyFields, loopyFields) = cir.fields.partition(fir => !shouldBotherAboutLooping(fir.tpe))
      val putFields = (nonLoopyFields ++ loopyFields).flatMap(fir => {
        if (sym.isModuleClass) {
          Nil
        } else if (fir.hasGetter) {
          def putField(getterLogic: Tree) = {
            def wrap(pickleLogic: Tree) = q"builder.putField(${fir.name}, b => $pickleLogic)"
            wrap {
              if (fir.tpe.typeSymbol.isEffectivelyFinal) q"""
                b.hintStaticallyElidedType()
                $getterLogic.pickleInto(b)
              """ else q"""
                val subPicklee: ${fir.tpe} = $getterLogic
                if (subPicklee == null || subPicklee.getClass == classOf[${fir.tpe}]) b.hintDynamicallyElidedType() else ()
                subPicklee.pickleInto(b)
              """
            }
          }
          if (fir.isPublic) List(putField(q"picklee.${TermName(fir.name)}"))
          else reflectively("picklee", fir)(fm => putField(q"$fm.get.asInstanceOf[${fir.tpe}]"))
        } else {
          // NOTE: this means that we've encountered a primary constructor parameter elided in the "constructors" phase
          // we can do nothing about that, so we don't serialize this field right now leaving everything to the unpickler
          // when deserializing we'll have to use the Unsafe.allocateInstance strategy
          Nil
        }
      })
      val endEntry = q"builder.endEntry()"
      if (shouldBotherAboutSharing(tpe)) {
        q"""
          import scala.reflect.runtime.universe._
          val oid = scala.pickling.internal.`package`.lookupPicklee(picklee)
          builder.hintOid(oid)
          $beginEntry
          if (oid == -1) { ..$putFields }
          $endEntry
        """
      } else {
        q"""
          import scala.reflect.runtime.universe._
          $beginEntry
          ..$putFields
          $endEntry
        """
      }
    }
    def pickleLogic = tpe match {
      case NothingTpe => c.abort(c.enclosingPosition, "cannot pickle Nothing") // TODO: report the serialization path that brought us here
      case _ => unifiedPickle
    }

    //println("trying to generate pickler for type " + tpe.toString)

    if (tpe.typeSymbol.isClass) {
      // if class is abstract return instance of `PicklerUnpicklerNotFound`.
      // this triggers the generation of a dispatch based on the runtime class of the picklee.
      val classSym = tpe.typeSymbol.asClass
      if (classSym.isAbstractClass) {
        //println("abstract class, returning PicklerUnpicklerNotFound")
        return q"new PicklerUnpicklerNotFound[$tpe]"
      }
    }

    val picklerName = c.fresh(syntheticPicklerName(tpe).toTermName)
    q"""
      implicit object $picklerName extends scala.pickling.SPickler[$tpe] with Generated {
        import scala.pickling._
        import scala.pickling.internal._
        import scala.pickling.`package`.PickleOps
        val format = implicitly[${format.tpe}]
        def pickle(picklee: $tpe, builder: PBuilder): Unit = $pickleLogic
      }
      $picklerName
    """
  }

  def dpicklerImpl[T: c.WeakTypeTag](format: c.Tree): c.Tree = {
    val tpe = weakTypeOf[T]
    val picklerName = c.fresh((syntheticBaseName(tpe) + "DPickler"): TermName)
    q"""
      implicit object $picklerName extends scala.pickling.DPickler[$tpe] {
        import scala.pickling._
        import scala.pickling.internal._
        import scala.pickling.`package`.PickleOps
        val format = implicitly[${format.tpe}]
      }
      $picklerName
    """
  }
}

// purpose of this macro: implementation of genUnpickler[T]. i.e., the macro that is selected via implicit
// search and which initiates the process of generating an unpickler for a given type T.
// NOTE: dispatch is done elsewhere. unpicklers generated by genUnpickler[T] only know how to process T
// but not its subclasses or the types convertible to it!
trait UnpicklerMacros extends Macro {
  def impl[T: c.WeakTypeTag](format: c.Tree): c.Tree = preferringAlternativeImplicits {
    import c.universe._
    import definitions._
    val tpe = weakTypeOf[T]
    val targs = tpe match { case TypeRef(_, _, targs) => targs; case _ => Nil }
    val sym = tpe.typeSymbol.asClass
    import irs._
    def unpicklePrimitive = q"reader.readPrimitive()"
    def unpickleObject = {
      def readField(name: String, tpe: Type) = q"reader.readField($name).unpickle[$tpe]"

      // TODO: validate that the tpe argument of unpickle and weakTypeOf[T] work together
      // NOTE: step 1) this creates an instance and initializes its fields reified from constructor arguments
      val cir = flattenedClassIR(tpe)
      val isPreciseType = targs.length == sym.typeParams.length && targs.forall(_.typeSymbol.isClass)
      val canCallCtor = !cir.fields.exists(_.isErasedParam) && isPreciseType
      // TODO: for ultimate loop safety, pendingFields should be hoisted to the outermost unpickling scope
      // For example, in the snippet below, when unpickling C, we'll need to move the b.c assignment not
      // just outside the constructor of B, but outside the enclosing constructor of C!
      //   class С(val b: B)
      //   class B(var c: C)
      // TODO: don't forget about the previous todo when describing the sharing algorithm for the paper
      // it's a very important detail, without which everything will crumble
      // no idea how to fix that, because hoisting might very well break reading order beyond repair
      // so that it becomes hopelessly unsync with writing order
      // nevertheless don't despair and try to prove whether this is or is not the fact
      // i was super scared that string sharing is going to fail due to the same reason, but it did not :)
      // in the worst case we can do the same as the interpreted runtime does - just go for allocateInstance
      val pendingFields = cir.fields.filter(fir =>
        fir.isNonParam ||
        (!canCallCtor && fir.isReifiedParam) ||
        shouldBotherAboutLooping(fir.tpe)
      )
      val instantiationLogic = {
        if (sym.isModuleClass) {
          q"${sym.module}"
        } else if (canCallCtor) {
          val ctorFirs = cir.fields.filter(_.param.isDefined)
          val ctorSig = ctorFirs.map(fir => (fir.param.get: Symbol, fir.tpe)).toMap
          val ctorArgs = {
            if (ctorSig.isEmpty) List(List())
            else {
              val ctorSym = ctorSig.head._1.owner.asMethod
              ctorSym.paramss.map(_.map(f => {
                val delayInitialization = pendingFields.exists(_.param.map(_ == f).getOrElse(false))
                if (delayInitialization) q"null" else readField(f.name.toString, ctorSig(f))
              }))
            }
          }
          q"new $tpe(...$ctorArgs)"
        } else {
          q"scala.concurrent.util.Unsafe.instance.allocateInstance(classOf[$tpe]).asInstanceOf[$tpe]"
        }
      }
      // NOTE: step 2) this sets values for non-erased fields which haven't been initialized during step 1
      val initializationLogic = {
        if (sym.isModuleClass || pendingFields.isEmpty) {
          instantiationLogic
        } else {
          val instance = TermName(tpe.typeSymbol.name + "Instance")

          val initPendingFields = pendingFields.flatMap(fir => {
            val readFir = readField(fir.name, fir.tpe)
            if (fir.isPublic && fir.hasSetter) List(q"$instance.${TermName(fir.name)} = $readFir".asInstanceOf[Tree])
            else if (fir.accessor.isEmpty) List()
            else reflectively(instance, fir)(fm => q"$fm.set($readFir)".asInstanceOf[Tree])
          })

          if (shouldBotherAboutSharing(tpe)) {
            q"""
              val oid = scala.pickling.internal.`package`.preregisterUnpicklee()
              val $instance = $instantiationLogic
              scala.pickling.internal.`package`.registerUnpicklee($instance, oid)
              ..$initPendingFields
              $instance
            """
          } else {
            q"""
              val $instance = $instantiationLogic
              ..$initPendingFields
              $instance
            """
          }         }
      }
      q"$initializationLogic"
    }
    def unpickleLogic = tpe match {
      case NullTpe => q"null"
      case NothingTpe => c.abort(c.enclosingPosition, "cannot unpickle Nothing") // TODO: report the deserialization path that brought us here
      case _ if tpe.isEffectivelyPrimitive || sym == StringClass => q"$unpicklePrimitive"
      case _ => q"$unpickleObject"
    }

    if (tpe.typeSymbol.isClass) {
      // if class is abstract return instance of `PicklerUnpicklerNotFound`.
      // this triggers the generation of a dispatch based on the runtime class of the picklee.
      val classSym = tpe.typeSymbol.asClass
      if (classSym.isAbstractClass) return q"new PicklerUnpicklerNotFound[$tpe]"
    }

    val unpicklerName = c.fresh(syntheticUnpicklerName(tpe).toTermName)
    q"""
      implicit object $unpicklerName extends scala.pickling.Unpickler[$tpe] with Generated {
        import scala.pickling._
        import scala.pickling.ir._
        import scala.pickling.internal._
        import scala.reflect.runtime.universe._
        val format = implicitly[${format.tpe}]
        def unpickle(tag: => FastTypeTag[_], reader: PReader): Any = $unpickleLogic
      }
      $unpicklerName
    """
  }
}

// purpose of this macro: implementation of PickleOps.pickle and pickleInto. i.e., this exists so as to:
// 1) perform dispatch based on the type of the argument
// 2) insert a call in the generated code to the genPickler macro (described above)
trait PickleMacros extends Macro {
  import c.universe._
  import definitions._

  def pickleTo[T: c.WeakTypeTag](output: c.Tree)(format: c.Tree): c.Tree = {
    val tpe = weakTypeOf[T]
    val q"${_}($pickleeArg)" = c.prefix.tree
    val endPickle = if (shouldBotherAboutCleaning(tpe)) q"clearPicklees()" else q"";
    q"""
      import scala.pickling._
      import scala.pickling.internal._
      val picklee: $tpe = $pickleeArg
      val builder = $format.createBuilder($output)
      picklee.pickleInto(builder)
      $endPickle
    """
  }

  def pickle[T: c.WeakTypeTag](format: c.Tree): c.Tree = {
    val tpe = weakTypeOf[T]
    val q"${_}($pickleeArg)" = c.prefix.tree
    val endPickle = if (shouldBotherAboutCleaning(tpe)) q"clearPicklees()" else q"";
    q"""
      import scala.pickling._
      import scala.pickling.internal._
      val picklee: $tpe = $pickleeArg
      val builder = $format.createBuilder()
      picklee.pickleInto(builder)
      $endPickle
      builder.result()
    """
  }

  def createPickler(tpe: c.Type, builder: c.Tree): c.Tree = q"""
    $builder.hintTag(implicitly[scala.pickling.FastTypeTag[$tpe]])
    implicitly[SPickler[$tpe]]
  """

  def genDispatchLogic(tpe: c.Type, builder: c.Tree): c.Tree = {
    val sym = tpe.typeSymbol
    def abstractTypeDispatch =
      q"""
        val customPickler = implicitly[SPickler[$tpe]]
        $builder.hintTag(implicitly[scala.pickling.FastTypeTag[$tpe]])
        customPickler
      """
    def finalDispatch = {
      if (sym.isNotNullable) createPickler(tpe, builder)
      else q"if (picklee != null) ${createPickler(tpe, builder)} else ${createPickler(NullTpe, builder)}"
    }
    def nonFinalDispatch = {
      val nullDispatch = CaseDef(Literal(Constant(null)), EmptyTree, createPickler(NullTpe, builder))
      val compileTimeDispatch = compileTimeDispatchees(tpe) filter (_ != NullTpe) map (subtpe =>
        CaseDef(Bind(TermName("clazz"), Ident(nme.WILDCARD)), q"clazz == classOf[$subtpe]", createPickler(subtpe, builder))
      )
      //TODO OPTIMIZE: do getClass.getClassLoader only once
      val runtimeDispatch = CaseDef(Ident(nme.WILDCARD), EmptyTree, q"SPickler.genPickler(this.getClass.getClassLoader, clazz)")
      // TODO: do we still want to use something like HasPicklerDispatch?
      q"""
        val customPickler = implicitly[SPickler[$tpe]]
        if (customPickler.isInstanceOf[PicklerUnpicklerNotFound[_]] || customPickler.isInstanceOf[Generated]) {
          val clazz = if (picklee != null) picklee.getClass else null
          ${Match(q"clazz", nullDispatch +: compileTimeDispatch :+ runtimeDispatch)}
        } else {
          // without Generated we would arrive here in two cases:
          // 1. we have found a custom pickler that can handle the abstract type tpe
          // 2. we have generated a pickler for a concrete superclass!
          // in the 2nd case we still have to do the dispatch!
          $builder.hintTag(implicitly[scala.pickling.FastTypeTag[$tpe]])
          customPickler
        }
      """
    }
    // NOTE: this has zero effect on performance...
    // def listDispatch = {
    //   val List(nullTpe, consTpe, nilTpe) = compileTimeDispatchees(tpe)
    //   q"""
    //     import scala.language.existentials
    //     if (picklee eq Nil) ${createPickler(nilTpe, builder)}
    //     else if (picklee eq null) ${createPickler(nullTpe, builder)}
    //     else ${createPickler(consTpe, builder)}
    //   """
    // }
    // if (sym == ListClass) listDispatch else
    if (sym.asType.isAbstractType) abstractTypeDispatch
    else if (sym.isEffectivelyFinal) finalDispatch
    else nonFinalDispatch
  }

  /** Used by the main `pickle` macro. Its purpose is to pickle the object that it's called on *into* the
   *  the `builder` which is passed to it as an argument.
   */
  def pickleInto[T: c.WeakTypeTag](builder: c.Tree): c.Tree = {
    val tpe = weakTypeOf[T].widen // TODO: I used widen to make module classes work, but I don't think it's okay to do that
    // val sym = tpe.typeSymbol.asClass // if this is called on an abstract type, an exception will be thrown (an abstract class will still fail, but it'll fail later)
    val q"${_}($pickleeArg)" = c.prefix.tree

    val dispatchLogic = genDispatchLogic(tpe, builder)

    q"""
      import scala.language.existentials
      import scala.pickling._
      import scala.pickling.internal._
      val picklee = $pickleeArg
      val pickler = $dispatchLogic
      pickler.asInstanceOf[SPickler[$tpe]].pickle(picklee, $builder)
    """
  }

  /* This macro first dispatches to the right SPickler, using the same dispatch logic
   * as in `pickleInto`, and then simply invokes `pickle` on it.
   */
  def dpicklerPickle[T: c.WeakTypeTag](picklee: c.Tree, builder: c.Tree): c.Tree = {
    val tpe = weakTypeOf[T].widen
    val sym = tpe.typeSymbol.asClass
    val dispatchLogic = genDispatchLogic(tpe, builder)
    q"""
      import scala.pickling._
      import scala.pickling.internal._
      val picklee = $picklee
      val pickler = $dispatchLogic
      pickler.asInstanceOf[SPickler[$tpe]].pickle($picklee, $builder)
    """
  }
}

// purpose of this macro: implementation of unpickle method on type Pickle, which does
// 1) dispatch to the correct unpickler based on the type of the input,
// 2) insert a call in the generated code to the genUnpickler macro (described above)
trait UnpickleMacros extends Macro {

  // TODO: currently this works with an assumption that sharing settings for unpickling are the same as for pickling
  // of course this might not be the case, so we should be able to read settings from the pickle itself
  // this is not going to be particularly pretty. unlike the fix for the runtime interpreter, this fix will be a bit of a shotgun one
  def pickleUnpickle[T: c.WeakTypeTag]: c.Tree = {
    import c.universe._
    val tpe = weakTypeOf[T]
    val pickleArg = c.prefix.tree
    q"""
      import scala.language.existentials
      import scala.pickling._
      import scala.pickling.internal._
      val pickle = $pickleArg
      val format = implicitly[${pickleFormatType(pickleArg)}]
      val reader = format.createReader(pickle, scala.pickling.internal.`package`.currentMirror)
      reader.unpickleTopLevel[$tpe]
    """
  }

  def readerUnpickle[T: c.WeakTypeTag]: c.Tree = {
    readerUnpickleHelper(false)
  }

  def readerUnpickleTopLevel[T: c.WeakTypeTag]: c.Tree = {
    readerUnpickleHelper(true)
  }

  def readerUnpickleHelper[T: c.WeakTypeTag](isTopLevel: Boolean = false): c.Tree = {
    import c.universe._
    import definitions._
    val tpe = weakTypeOf[T]
    val sym = tpe.typeSymbol
    val readerArg = c.prefix.tree

    def createUnpickler(tpe: Type) = q"implicitly[Unpickler[$tpe]]"
    def finalDispatch = {
      if (sym.isNotNullable) createUnpickler(tpe)
      else q"""
        val tag = scala.pickling.FastTypeTag(typeString)
        if (tag.key == scala.pickling.FastTypeTag.Null.key) ${createUnpickler(NullTpe)}
        else if (tag.key == scala.pickling.FastTypeTag.Ref.key) ${createUnpickler(RefTpe)}
        else ${createUnpickler(tpe)}
      """
    }

    val customDispatch = CaseDef(Ident(nme.WILDCARD), EmptyTree, q"customUnpickler")
    val refDispatch = CaseDef(Literal(Constant(FastTypeTag.Ref.key)), EmptyTree, createUnpickler(typeOf[refs.Ref]))

    def nonFinalDispatch = {
      val compileTimeDispatch = compileTimeDispatchees(tpe) map (subtpe => {
        // TODO: do we still want to use something like HasPicklerDispatch (for unpicklers it would be routed throw tpe's companion)?
        CaseDef(Literal(Constant(subtpe.key)), EmptyTree, createUnpickler(subtpe))
      })
      val runtimeDispatch = CaseDef(Ident(nme.WILDCARD), EmptyTree, q"""
        val tag = scala.pickling.FastTypeTag(typeString)
        Unpickler.genUnpickler(reader.mirror, tag)
      """)

      q"""
        val customUnpickler = implicitly[Unpickler[$tpe]]
        if (customUnpickler.isInstanceOf[PicklerUnpicklerNotFound[_]] || customUnpickler.isInstanceOf[Generated]) {
          ${Match(q"typeString", compileTimeDispatch :+ refDispatch :+ runtimeDispatch)}
        } else {
          ${Match(q"typeString", List(refDispatch) :+ customDispatch)}
        }
      """
    }

    def abstractTypeDispatch =
      q"""
        val customUnpickler = implicitly[Unpickler[$tpe]]
        ${Match(q"typeString", List(refDispatch) :+ customDispatch)}
      """

    val staticHint = if (sym.isEffectivelyFinal && !isTopLevel) (q"reader.hintStaticallyElidedType()": Tree) else q"";
    val dispatchLogic =
      if (sym.asType.isAbstractType) abstractTypeDispatch
      else if (sym.isEffectivelyFinal) finalDispatch
      else nonFinalDispatch
    val unpickleeCleanup = if (isTopLevel && shouldBotherAboutCleaning(tpe)) q"clearUnpicklees()" else q""

    q"""
      val reader = $readerArg
      reader.hintTag(implicitly[scala.pickling.FastTypeTag[$tpe]])
      $staticHint
      val typeString = reader.beginEntryNoTag()
      val unpickler = $dispatchLogic
      val result = unpickler.unpickle({ scala.pickling.FastTypeTag(typeString) }, reader)
      reader.endEntry()
      $unpickleeCleanup
      result.asInstanceOf[$tpe]
    """
  }
}
