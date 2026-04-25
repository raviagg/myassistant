package com.myassistant.api.models

import com.myassistant.domain.{Household, PersonHousehold}
import io.circe.Codec

import java.time.Instant
import java.util.UUID

/** HTTP request body for POST /households. */
final case class CreateHouseholdRequest(name: String) derives Codec.AsObject

/** HTTP request body for PATCH /households/:id. */
final case class UpdateHouseholdRequest(name: Option[String]) derives Codec.AsObject

/** HTTP response body for a single household. */
final case class HouseholdResponse(
    id:        UUID,
    name:      String,
    createdAt: Instant,
    updatedAt: Instant,
) derives Codec.AsObject

object HouseholdResponse:
  /** Build a HouseholdResponse from the domain Household. */
  def fromDomain(h: Household): HouseholdResponse =
    HouseholdResponse(id = h.id, name = h.name, createdAt = h.createdAt, updatedAt = h.updatedAt)

/** HTTP response body for a household membership record. */
final case class PersonHouseholdResponse(
    personId:    UUID,
    householdId: UUID,
    createdAt:   Instant,
) derives Codec.AsObject

object PersonHouseholdResponse:
  /** Build a PersonHouseholdResponse from the domain PersonHousehold. */
  def fromDomain(ph: PersonHousehold): PersonHouseholdResponse =
    PersonHouseholdResponse(personId = ph.personId, householdId = ph.householdId, createdAt = ph.createdAt)

/** HTTP request body for adding a member to a household. */
final case class AddMemberRequest(personId: UUID) derives Codec.AsObject
