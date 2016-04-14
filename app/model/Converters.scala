package model

import org.joda.time.{DateTime, DateTimeZone}
import play.api.Logger
import play.api.libs.functional.syntax._
import play.api.libs.json._

object Converters {

  def emailToFormBody(email: EmailToSent): Map[String, String] = {
    val mandatoryFields = Map(
      "from" -> email.from,
      "to" -> email.to,
      "subject" -> email.subject.getOrElse(""),
      "text" -> email.text,
      "html" -> email.htmlBody.getOrElse("")
    )
    (mandatoryFields)
  }

}
