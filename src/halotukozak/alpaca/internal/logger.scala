package halotukozak
package alpaca.internal

import java.io.{BufferedWriter, FileWriter}
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap

private[internal] object logger:
  inline def toFile(path: String, replace: Boolean)(content: => Shown)(using debugSettings: DebugSettings): Unit =
    debugSettings.debugDirectory.foreach: dir =>
      val file = Path.of(dir).resolve(path)
      if replace then this.replace(file)(content) else this.append(file)(content)

  private val writerCache = new ConcurrentHashMap[Path, BufferedWriter]

  def append(path: Path)(content: Shown): Unit = writerCache.compute(
    path,
    (p, existing) => (if existing == null then createWriter(p, false) else existing).tap(_.write(content)),
  ): Unit

  def replace(path: Path)(content: Shown): Unit = writerCache
    .compute(
      path,
      (p, existing) =>
        if existing != null then existing.close()
        createWriter(p, true).tap(_.write(content)),
    ): Unit

  private def createWriter(path: Path, replace: Boolean): BufferedWriter =
    if path.getParent != null then Files.createDirectories(path.getParent): Unit
    new BufferedWriter(new FileWriter(path.toFile, !replace))
