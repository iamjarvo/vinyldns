/*
 * Copyright 2018 Comcast Cable Communications Management, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vinyldns.api.domain

import cats.implicits._, cats.data._
import vinyldns.api.domain.ValidationImprovements._
import vinyldns.api.domain.record.RecordType.{RecordType, _}

import scala.util.Try
import scala.util.matching.Regex

/*
  Object to house common domain validations
 */
object DomainValidations {
  val validEmailRegex: Regex = """^([0-9a-zA-Z_\-\.]+)@([0-9a-zA-Z_\-\.]+)\.([a-zA-Z]{2,5})$""".r
  val validFQDNRegex: Regex =
    """^(?:([0-9a-zA-Z]{1,63}|[0-9a-zA-Z]{1}[0-9a-zA-Z\-\/]{0,61}[0-9a-zA-Z]{1})\.)*$""".r
  val validIpv4Regex: Regex =
    """^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$""".r
  val validIpv6Regex: Regex =
    """^(
      #([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|
      #([0-9a-fA-F]{1,4}:){1,7}:|
      #([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|
      #([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|
      #([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|
      #([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|
      #([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|
      #[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|
      #:((:[0-9a-fA-F]{1,4}){1,7}|:)|
      #fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|
      #::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|
      #(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|
      #([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]
      #|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])
      #)$""".stripMargin('#').replaceAll("\n", "").r
  val PORT_MIN_VALUE: Int = 0
  val PORT_MAX_VALUE: Int = 65535
  val HOST_MIN_LENGTH: Int = 2
  val HOST_MAX_LENGTH: Int = 255
  val TTL_MAX_LENGTH: Int = 2147483647
  val TTL_MIN_LENGTH: Int = 30
  val TXT_TEXT_MIN_LENGTH: Int = 1
  val TXT_TEXT_MAX_LENGTH: Int = 64764
  val MX_PREFERENCE_MIN_VALUE: Int = 0
  val MX_PREFERENCE_MAX_VALUE: Int = 65535

  def validateEmail(email: String): ValidatedNel[DomainValidationError, String] =
    /*
     Basic e-mail checking that also blocks some positive e-mails (by RFC standards)
     (eg. e-mails containing hex and special characters.)
     */
    if (validEmailRegex.findFirstIn(email).isDefined) email.validNel
    else InvalidEmail(email).invalidNel

  def validateHostName(name: String): ValidatedNel[DomainValidationError, String] = {
    /*
      Label rules are as follows (from RFC 952; detailed in RFC 1034):
        - Starts with a letter, OR digit (as of RFC 1123)
        - Interior contains letter, digit or hyphen
        - Ends with a letter or digit
      All possible labels permutations:
        - A single letter/digit: [0-9a-zA-Z]{1}
        - A combination of 1-63 letters/digits: [0-9a-zA-Z]{1,63}
        - A single letter/digit followed by up to 61 letters, digits, hyphens or slashes
        and ending with a letter/digit:[0-9a-zA-Z]{1}[0-9a-zA-Z\-]{0,61}[0-9a-zA-Z]{1}
      A valid domain name is a series of one or more <label>s,
      joined by dots/slashes and terminating on a zero-length <label> (ie. dot)
     */
    val checkRegex = validFQDNRegex
      .findFirstIn(name)
      .map(_.validNel)
      .getOrElse(InvalidDomainName(name).invalidNel)
    val checkLength = validateStringLength(name, Some(HOST_MIN_LENGTH), HOST_MAX_LENGTH)

    checkRegex.combine(checkLength).map(_ => name)
  }

  def validateIpv4Address(address: String): ValidatedNel[DomainValidationError, String] =
    validIpv4Regex
      .findFirstIn(address)
      .map(_.validNel)
      .getOrElse(InvalidIpv4Address(address).invalidNel)

  def validateIpv6Address(address: String): ValidatedNel[DomainValidationError, String] =
    validIpv6Regex
      .findFirstIn(address)
      .map(_.validNel)
      .getOrElse(InvalidIpv6Address(address).invalidNel)

  def validatePort(port: String): ValidatedNel[DomainValidationError, String] =
    Try(port.toInt)
      .map {
        case ok if ok >= PORT_MIN_VALUE && ok <= PORT_MAX_VALUE => port.validNel
        case outOfRange =>
          InvalidPortNumber(outOfRange.toString, PORT_MIN_VALUE, PORT_MAX_VALUE).invalidNel
      }
      .getOrElse(InvalidPortNumber(port, PORT_MIN_VALUE, PORT_MAX_VALUE).invalidNel)

  def validateStringLength(
      value: Option[String],
      minInclusive: Option[Int],
      maxInclusive: Int): ValidatedNel[DomainValidationError, Option[String]] =
    validateIfDefined(value) { d =>
      validateStringLength(d, minInclusive, maxInclusive)
    }

  def validateStringLength(
      value: String,
      minInclusive: Option[Int],
      maxInclusive: Int): ValidatedNel[DomainValidationError, String] =
    if (minInclusive.forall(m => value.length >= m) && value.length <= maxInclusive)
      value.validNel
    else InvalidLength(value, minInclusive.getOrElse(0), maxInclusive).invalidNel

  def validateKnownRecordTypes(
      types: Set[RecordType]): ValidatedNel[DomainValidationError, Set[RecordType]] = {
    val a: List[ValidatedNel[DomainValidationError, RecordType]] =
      types.toList.map(validateKnownRecordType)
    a.sequence.map(_.toSet)
  }

  def validateKnownRecordType(rType: RecordType): ValidatedNel[DomainValidationError, RecordType] =
    rType match {
      case UNKNOWN => InvalidRecordType(rType.toString).invalidNel
      case _ => rType.validNel
    }

  def validateTrailingDot(value: String): ValidatedNel[DomainValidationError, String] =
    if (value.endsWith(".")) value.validNel else InvalidDomainName(value).invalidNel

  def validateTTL(ttl: Long): ValidatedNel[DomainValidationError, Long] =
    if (ttl >= TTL_MIN_LENGTH && ttl <= TTL_MAX_LENGTH) ttl.validNel
    else InvalidTTL(ttl).invalidNel[Long]

  def validateTxtTextLength(value: String): ValidatedNel[DomainValidationError, String] =
    validateStringLength(value, Some(TXT_TEXT_MIN_LENGTH), TXT_TEXT_MAX_LENGTH)

  def validateMxPreference(pref: Int): ValidatedNel[DomainValidationError, Int] =
    if (pref >= MX_PREFERENCE_MIN_VALUE && pref <= MX_PREFERENCE_MAX_VALUE) pref.validNel
    else InvalidMxPreference(pref).invalidNel[Int]
}
