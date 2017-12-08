package skiticket.utils

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import javax.crypto.Mac

import scodec.Attempt.{Failure, Successful}
import scodec.bits.BitVector
import scodec.codecs._
import scodec.{Attempt, Codec, DecodeResult, Err, SizeBound}
import skiticket.utils.ByteSeqExtension._

package object codecs {
    def presentIfNonZero[T](codec: Codec[T], zeroVal: T): Codec[Option[T]] = new
                    Codec[Option[T]] {
        override def decode(bits: BitVector): Attempt[DecodeResult[Option[T]]] = {
            codec.map { x =>
                if (x == zeroVal) {
                    None
                } else {
                    Some(x)
                }
            }.decode(bits)
        }

        override def encode(value: Option[T]): Attempt[BitVector] = {
            value match {
                case Some(x) =>
                    codec.encode(x)
                case None =>
                    codec.encode(zeroVal)
            }
        }

        override def sizeBound: SizeBound = codec.sizeBound
    }

    def offsetDateTimeCodec[T](baseDate: LocalDateTime, numCodec: Codec[T])
                              (implicit integral: Integral[T])
    : Codec[LocalDateTime] =
        numCodec.exmap(
            num =>
                Attempt.successful(baseDate.plusSeconds(integral.toLong(num))),
            { dateTime =>
                val seconds = ChronoUnit.SECONDS.between(baseDate, dateTime)
                Attempt.successful(integral.fromInt(seconds.toInt))
            })

    def macCodec[T](macInstance: Mac, suffix: BitVector)(implicit codec: Codec[T]): Codec[T] =
        filtered(codec, new
                        Codec[BitVector] {
            override def encode(value: BitVector): Attempt[BitVector] = {
                val bytes = value.toByteArray

                macInstance.update(bytes)
                macInstance.update(suffix.toByteArray)
                val mac = macInstance.doFinal()
                Attempt.successful(value ++ BitVector(mac))
            }

            private val macLength = macInstance.getMacLength

            override def sizeBound: SizeBound =
                codec.sizeBound + SizeBound.exact(macLength * 8)

            override def decode(bits: BitVector)
            : Attempt[DecodeResult[BitVector]] = {
                val bytes = bits.bytes
                val (dataBytes, macBytes) = bytes.splitAt(bytes.size - macLength)
                assert(macBytes.length == macLength)

                macInstance.update(dataBytes.toArray)
                macInstance.update(suffix.toByteArray)
                val macComputed = macInstance.doFinal()

                if (macComputed sameElements macBytes.toArray) {
                    Attempt.successful(
                        DecodeResult(
                            dataBytes.toBitVector,
                            BitVector.empty
                        )
                    )
                } else {
                    Attempt.failure(Err(s"MAC did not match (expected ${
                        macBytes
                                .toHex
                    }, got ${macComputed.toSeq.toHexString}"))
                }
            }
        })

    def knownSizeSeq[T](size: Int, codec: Codec[T]): Codec[Seq[T]] = new
                    Codec[Seq[T]] {
        require(codec.sizeBound.exact.isDefined)

        override def encode(value: Seq[T]): Attempt[BitVector] = {
            val attempt = value.map(codec.encode)
                    .fold(Attempt.successful(BitVector.empty)) {
                        case (Successful(bits1), Successful(bits2)) =>
                            Attempt.successful(bits1 ++ bits2)
                        case (Failure(err1), Failure(err2)) =>
                            Attempt.failure(Err(s"$err1\n$err2"))
                        case (Failure(err), _) =>
                            Attempt.failure(err)
                        case (_, Failure(err)) =>
                            Attempt.failure(err)
                    }

            attempt
        }

        override def sizeBound: SizeBound = codec.sizeBound * size

        override def decode(bits: BitVector): Attempt[DecodeResult[Seq[T]]] = {
            val elementSize = codec.sizeBound.exact.get
            val attempts = bits
                    .take(elementSize * size)
                    .grouped(elementSize)
                    .zipWithIndex
                    .map { case (elemBits, i) => (codec.decode(elemBits), i) }

            val errors = attempts.collect {
                case (Failure(err), i) =>
                    s"$i -> $err"
            }

            if (errors.isEmpty) {
                Attempt.successful(DecodeResult(
                    attempts.map(x => x._1.require.value),
                    bits.drop(elementSize * size)
                ))
            } else {
                Attempt.failure(Err(errors.mkString("\n")))
            }
        }
    }
}

