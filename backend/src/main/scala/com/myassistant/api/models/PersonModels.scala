package com.myassistant.api.models

import com.myassistant.domain.{CreatePerson, Gender, Person, UpdatePerson}
import io.circe.Codec

import java.time.{Instant, LocalDate}
import java.util.UUID

/** HTTP request body for POST /persons. */
final case class CreatePersonRequest(
    fullName:       String,
    gender:         String,
    dateOfBirth:    Option[LocalDate],
    preferredName:  Option[String],
    userIdentifier: Option[String],
) derives Codec.AsObject:

  /** Convert to domain CreatePerson; caller must validate gender string beforehand. */
  def toDomain: Either[String, CreatePerson] =
    parseGender(gender).map(g =>
      CreatePerson(
        fullName       = fullName,
        gender         = g,
        dateOfBirth    = dateOfBirth,
        preferredName  = preferredName,
        userIdentifier = userIdentifier,
      )
    )

/** HTTP request body for PATCH /persons/:id. */
final case class UpdatePersonRequest(
    fullName:       Option[String],
    gender:         Option[String],
    dateOfBirth:    Option[LocalDate],
    preferredName:  Option[String],
    userIdentifier: Option[String],
) derives Codec.AsObject:

  /** Convert to domain UpdatePerson; caller must validate gender string beforehand. */
  def toDomain: Either[String, UpdatePerson] =
    gender match
      case None => Right(UpdatePerson(fullName, None, dateOfBirth, preferredName, userIdentifier))
      case Some(g) =>
        parseGender(g).map(pg =>
          UpdatePerson(fullName, Some(pg), dateOfBirth, preferredName, userIdentifier)
        )

/** HTTP response body for a single person. */
final case class PersonResponse(
    id:             UUID,
    fullName:       String,
    gender:         String,
    dateOfBirth:    Option[LocalDate],
    preferredName:  Option[String],
    userIdentifier: Option[String],
    createdAt:      Instant,
    updatedAt:      Instant,
) derives Codec.AsObject

object PersonResponse:
  /** Build a PersonResponse from the domain Person. */
  def fromDomain(p: Person): PersonResponse =
    PersonResponse(
      id             = p.id,
      fullName       = p.fullName,
      gender         = p.gender.toString.toLowerCase,
      dateOfBirth    = p.dateOfBirth,
      preferredName  = p.preferredName,
      userIdentifier = p.userIdentifier,
      createdAt      = p.createdAt,
      updatedAt      = p.updatedAt,
    )

/** Parse a lowercase gender string to a domain Gender value. */
private def parseGender(s: String): Either[String, Gender] = s.toLowerCase match
  case "male"   => Right(Gender.Male)
  case "female" => Right(Gender.Female)
  case other    => Left(s"Unknown gender: '$other'. Valid values: male, female")
