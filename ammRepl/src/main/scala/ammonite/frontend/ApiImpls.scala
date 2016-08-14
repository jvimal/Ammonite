package ammonite.frontend

import java.io.File

import ammonite.interp._
import ammonite.ops.{Path, read}
import ammonite.util.Util._
import ammonite.util._
import pprint.{Config, PPrint}

import scala.collection.mutable

class SessionApiImpl(eval: Evaluator) extends Session{
  val namedFrames = mutable.Map.empty[String, List[Frame]]
  def frames = eval.frames
  def childFrame(parent: Frame) = new Frame(
    new SpecialClassLoader(
      parent.classloader,
      parent.classloader.classpathSignature
    ),
    new SpecialClassLoader(
      parent.pluginClassloader,
      parent.pluginClassloader.classpathSignature
    ),
    parent.imports,
    parent.classpath
  )

  def save(name: String = "") = {
    if (name != "") namedFrames(name) = eval.frames
    eval.frames = childFrame(frames.head) :: frames
  }

  def pop(num: Int = 1) = {
    var next = eval.frames
    for(i <- 0 until num){
      if (next.tail != Nil) next = next.tail
    }
    val out = SessionChanged.delta(eval.frames.head, next.head)
    eval.frames = childFrame(next.head) :: next
    out
  }
  def load(name: String = "") = {
    val next = if (name == "") eval.frames.tail else namedFrames(name)
    val out = SessionChanged.delta(eval.frames.head, next.head)
    eval.frames = childFrame(next.head) :: next
    out
  }

  def delete(name: String) = {
    namedFrames.remove(name)
  }
  save()
}
class ReplApiImpl(val interp: Interpreter,
                  width0: => Int,
                  height0: => Int,
                  colors0: Ref[Colors],
                  prompt0: Ref[String],
                  history0: => History,
                  sess0: Session) extends DefaultReplAPI{
  import interp._

  def lastException = interp.lastException

  def imports = Preprocessor.importBlock(eval.frames.head.imports)
  val colors = colors0
  val prompt = prompt0
  //    val frontEnd = frontEnd0

  lazy val resolvers =
    Ref(ammonite.tools.Resolvers.defaultResolvers)

  object load extends DefaultLoadJar with Load {

    def handleClasspath(jar: File) = handleEvalClasspath(jar)

    def apply(line: String) = processExec(line) match{
      case Res.Failure(ex, s) => throw new CompilationError(s)
      case Res.Exception(t, s) => throw t
      case _ =>
    }

    def exec(file: Path): Unit = apply(normalizeNewlines(read(file)))

    def module(file: Path) = {
      val (pkg, wrapper) = Util.pathToPackageWrapper(file, wd)
      processModule(
        ImportHook.Source.File(wd/"Main.sc"),
        normalizeNewlines(read(file)),
        wrapper,
        pkg,
        true,
        ""
      ) match{
        case Res.Failure(ex, s) => throw new CompilationError(s)
        case Res.Exception(t, s) => throw t
        case x => //println(x)
      }
      reInit()
    }

    object plugin extends DefaultLoadJar {
      def handleClasspath(jar: File) = handlePluginClasspath(jar)
    }

  }
  implicit def tprintColors = pprint.TPrintColors(
    typeColor = colors().`type`()
  )
  implicit val codeColors = new CodeColors{
    def comment = colors().comment()
    def `type` = colors().`type`()
    def literal = colors().literal()
    def keyword = colors().keyword()
    def ident = colors().ident()
  }
  implicit lazy val pprintConfig: Ref[pprint.Config] = {
    Ref.live[pprint.Config]( () =>
      pprint.Config.apply(
        width = width,
        height = height / 2,
        colors = pprint.Colors(
          colors().literal(),
          colors().prefix()
        )
      )
    )

  }

  def show[T: PPrint](implicit cfg: Config) = (t: T) => {
    pprint.tokenize(t, height = 0)(implicitly[PPrint[T]], cfg).foreach(printer.out)
    printer.out(newLine)
  }
  def show[T: PPrint](t: T,
                      width: Integer = null,
                      height: Integer = 0,
                      indent: Integer = null,
                      colors: pprint.Colors = null)
                     (implicit cfg: Config = Config.Defaults.PPrintConfig) = {


    pprint.tokenize(t, width, height, indent, colors)(implicitly[PPrint[T]], cfg)
      .foreach(printer.out)
    printer.out(newLine)
  }

  def search(target: scala.reflect.runtime.universe.Type) = {
    interp.compiler.search(target)
  }
  def compiler = interp.compiler.compiler
  def newCompiler() = init()
  def fullHistory = storage.fullHistory()
  def history = history0


  def width = 0

  def height = 0

  object sess extends Session {
    def frames = eval.frames
    def save(name: String) = sess0.save(name)
    def delete(name: String) = sess0.delete(name)

    def pop(num: Int = 1) = {
      val res = sess0.pop(num)
      reInit()
      res
    }
    def load(name: String = "") = {
      val res = sess0.load(name)
      reInit()
      res
    }
  }

}