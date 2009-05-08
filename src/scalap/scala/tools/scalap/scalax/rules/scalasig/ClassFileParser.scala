package scala.tools.scalap.scalax.rules.scalasig


import java.io.IOException

import scala._
import scala.Predef._

import scalax.rules.Error

object ByteCode {
  def apply(bytes : Array[Byte]) = new ByteCode(bytes, 0, bytes.length)

  def forClass(clazz : Class[_]) = {
    val name = clazz.getName
    val subPath = name.substring(name.lastIndexOf('.') + 1) + ".class"
    val in = clazz.getResourceAsStream(subPath)

    try {
      var rest = in.available()
      val bytes = new Array[Byte](rest)
      while (rest > 0) {
        val res = in.read(bytes, bytes.length - rest, rest)
        if (res == -1) throw new IOException("read error")
        rest -= res
      }
      ByteCode(bytes)

    } finally {
      in.close()
    }
  }
}

/** Represents a chunk of raw bytecode.  Used as input for the parsers
 */
class ByteCode(val bytes : Array[Byte], val pos : Int, val length : Int) {

  assert(pos >= 0 && length >= 0 && pos + length <= bytes.length)

  def nextByte = if (length == 0) Failure else Success(drop(1), bytes(pos))
  def next(n : Int) = if (length >= n) Success(drop(n), take(n)) else Failure

  def take(n : Int) = new ByteCode(bytes, pos, n)
  def drop(n : Int) = new ByteCode(bytes, pos + n, length - n)

  def fold[X](x : X)(f : (X, Byte) => X) : X = {
    var result = x
    var i = pos
    while (i < pos + length) {
      result = f(result, bytes(i))
      i += 1
    }
    result
  }

  override def toString = length + " bytes"

  def toInt = fold(0) { (x, b) => (x << 8) + (b & 0xFF)}
  def toLong = fold(0L) { (x, b) => (x << 8) + (b & 0xFF)}

  // NOTE the UTF8 decoder in the Scala compiler is broken for pos > 0
  // TODO figure out patch and submit
  def toUTF8String = {
    val sb = new StringBuilder(length)
    var i = pos
    val end = pos + length
    while (i < end) {
      var b = bytes(i) & 0xFF
      i += 1
      if (b >= 0xE0) {
        b = ((b & 0x0F) << 12) | (bytes(i) & 0x3F) << 6
        b = b | (bytes(i+1) & 0x3F)
        i += 2
      } else if (b >= 0xC0) {
        b = ((b & 0x1F) << 6) | (bytes(i) & 0x3F)
        i += 1
      }
      sb += b.toChar
    }
    sb.toString
  }

  def byte(i : Int) = bytes(pos) & 0xFF
}

/** Provides rules for parsing byte-code.
*/
trait ByteCodeReader extends RulesWithState {
  type S = ByteCode
  type Parser[A] = Rule[A, String]

  val byte = apply(_ nextByte)

  val u1 = byte ^^ (_ & 0xFF)
  val u2 = bytes(2) ^^ (_ toInt)
  val u4 = bytes(4) ^^ (_ toInt) // should map to Long??

  def bytes(n : Int) = apply(_ next n)



}

object ClassFileParser extends ByteCodeReader {
  def parse(byteCode : ByteCode) = expect(classFile)(byteCode)

  val magicNumber = (u4 filter (_ == 0xCAFEBABE)) | error("Not a valid class file")
  val version = u2 ~ u2 ^^ { case minor ~ major => (major,  minor) }
  val constantPool = (u2 ^^ ConstantPool) >> repeatUntil(constantPoolEntry)(_ isFull)

  // NOTE currently most constants just evaluate to a string description
  // TODO evaluate to useful values
  val utf8String = (u2 >> bytes) ^^ add1 { raw => pool => raw.toUTF8String }
  val intConstant = u4 ^^ add1 { x => pool => x }
  val floatConstant = bytes(4) ^^ add1 { raw => pool => "Float: TODO" }
  val longConstant = bytes(8) ^^ add2 { raw => pool => raw.toLong }
  val doubleConstant = bytes(8) ^^ add2 { raw => pool => "Double: TODO" }
  val classRef = u2 ^^ add1 { x => pool => "Class: " + pool(x) }
  val stringRef = u2 ^^ add1 { x => pool => "String: " + pool(x) }
  val fieldRef = memberRef("Field")
  val methodRef = memberRef("Method")
  val interfaceMethodRef = memberRef("InterfaceMethod")
  val nameAndType = u2 ~ u2 ^^ add1 { case name ~ descriptor => pool => "NameAndType: " + pool(name) + ", " + pool(descriptor) }

  val constantPoolEntry = u1 >> {
    case 1 => utf8String
    case 3 => intConstant
    case 4 => floatConstant
    case 5 => longConstant
    case 6 => doubleConstant
    case 7 => classRef
    case 8 => stringRef
    case 9 => fieldRef
    case 10 => methodRef
    case 11 => interfaceMethodRef
    case 12 => nameAndType
  }

  val interfaces = u2 >> u2.times

  val attribute = u2 ~ (u4 >> bytes) ^~^ Attribute
  val attributes = u2 >> attribute.times

  val field = u2 ~ u2 ~ u2 ~ attributes ^~~~^ Field
  val fields = u2 >> field.times

  val method = u2 ~ u2 ~ u2 ~ attributes ^~~~^ Method
  val methods = u2 >> method.times

  val header = magicNumber -~ u2 ~ u2 ~ constantPool ~ u2 ~ u2 ~ u2 ~ interfaces ^~~~~~~^ ClassFileHeader
  val classFile = header ~ fields ~ methods ~ attributes ~- !u1 ^~~~^ ClassFile

  // TODO create a useful object, not just a string
  def memberRef(description : String) = u2 ~ u2 ^^ add1 {
    case classRef ~ nameAndTypeRef => pool => description + ": " + pool(classRef) + ", " + pool(nameAndTypeRef)
  }

  def add1[T](f : T => ConstantPool => Any)(raw : T)(pool : ConstantPool) = pool add f(raw)
  def add2[T](f : T => ConstantPool => Any)(raw : T)(pool : ConstantPool) = pool add f(raw) add { pool => "<empty>" }
}

case class ClassFile(
    header : ClassFileHeader,
    fields : Seq[Field],
    methods : Seq[Method],
    attributes : Seq[Attribute]) {

  def majorVersion = header.major
  def minorVersion = header.minor

  def className = constant(header.classIndex)
  def superClass = constant(header.superClassIndex)
  def interfaces = header.interfaces.map(constant)

  def constant(index : Int) = header.constants(index)

  def attribute(name : String) = attributes.find { attrib => constant(attrib.nameIndex) == name }
}

case class Attribute(nameIndex : Int, byteCode : ByteCode)
case class Field(flags : Int, nameIndex : Int, descriptorIndex : Int, attributes : Seq[Attribute])
case class Method(flags : Int, nameIndex : Int, descriptorIndex : Int, attributes : Seq[Attribute])

case class ClassFileHeader(
    minor : Int,
    major : Int,
    constants : ConstantPool,
    flags : Int,
    classIndex : Int,
    superClassIndex : Int,
    interfaces : Seq[Int]) {

  def constant(index : Int) = constants(index)
}

case class ConstantPool(len : Int) {
  val size = len - 1

  private val buffer = new scala.collection.mutable.ArrayBuffer[ConstantPool => Any]
  private val values = Array.make[Option[Any]](size, None)

  def isFull = buffer.length >= size

  def apply(index : Int) = {
    // Note constant pool indices are 1-based
    val i = index - 1
    values(i) getOrElse {
      val value = buffer(i)(this)
      buffer(i) = null
      values(i) = Some(value)
      value
    }
  }

  def add(f : ConstantPool => Any) = {
    buffer + f
    this
  }
}

