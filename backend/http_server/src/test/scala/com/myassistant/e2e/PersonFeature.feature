Feature: Person Management

  Background:
    Given the server is running and I am authenticated

  Scenario: Create and retrieve a person
    When I POST to "/api/v1/persons" with body:
      """
      {"fullName": "Ravi Aggarwal", "gender": "Male"}
      """
    Then the response status is 201
    And the response body contains "Ravi Aggarwal"
    When I GET the created person by id
    Then the response status is 200
    And the response body contains "Ravi Aggarwal"

  Scenario: Get a non-existent person returns 404
    When I GET "/api/v1/persons/00000000-0000-0000-0000-000000000000"
    Then the response status is 404

  Scenario: Create person requires fullName
    When I POST to "/api/v1/persons" with body:
      """
      {"gender": "Male"}
      """
    Then the response status is 400

  Scenario: List persons after creation
    When I POST to "/api/v1/persons" with body:
      """
      {"fullName": "Priya Sharma", "gender": "Female"}
      """
    Then the response status is 201
    When I GET "/api/v1/persons"
    Then the response status is 200
    And the response body contains "Priya Sharma"

  Scenario: Update an existing person
    When I POST to "/api/v1/persons" with body:
      """
      {"fullName": "Temp Name", "gender": "Male"}
      """
    Then the response status is 201
    When I PATCH the created person with body:
      """
      {"fullName": "Updated Name"}
      """
    Then the response status is 200
    And the response body contains "Updated Name"

  Scenario: Delete an existing person
    When I POST to "/api/v1/persons" with body:
      """
      {"fullName": "Delete Me", "gender": "Female"}
      """
    Then the response status is 201
    When I DELETE the created person by id
    Then the response status is 204

  Scenario: Create person with date of birth
    When I POST to "/api/v1/persons" with body:
      """
      {"fullName": "Born Person", "gender": "Male", "dateOfBirth": "1990-06-15"}
      """
    Then the response status is 201
    And the response body contains "1990-06-15"

  Scenario: List persons filtered by household membership
    Given a person and household exist for person filter tests
    When I add the person to the household for person filter
    Then the response status is 204
    When I GET persons in the household
    Then the response status is 200
    And the person filter list contains the created person

  Scenario: Update person with multiple fields
    When I POST to "/api/v1/persons" with body:
      """
      {"fullName": "Multi Field Person", "gender": "Male"}
      """
    Then the response status is 201
    When I PATCH the created person with body:
      """
      {"gender": "Female", "dateOfBirth": "1985-03-20", "preferredName": "Mfp"}
      """
    Then the response status is 200
    And the response body contains "1985-03-20"
    And the response body contains "Mfp"

  Scenario: Delete person with relationship dependency returns 409
    Given a person with a relationship exists for delete constraint tests
    When I DELETE the constrained person
    Then the response status is 409

  Scenario: Delete non-existent person returns 404
    When I DELETE "/api/v1/persons/00000000-0000-0000-0000-000000000000"
    Then the response status is 404

  Scenario: Update person with empty patch returns unchanged person
    When I POST to "/api/v1/persons" with body:
      """
      {"fullName": "Empty Patch Person", "gender": "Male"}
      """
    Then the response status is 201
    When I PATCH the created person with body:
      """
      {}
      """
    Then the response status is 200
    And the response body contains "Empty Patch Person"

  Scenario: Update non-existent person returns 404
    When I PATCH "/api/v1/persons/00000000-0000-0000-0000-000000000000" with body:
      """
      {"fullName": "Ghost Person"}
      """
    Then the response status is 404

  Scenario: Update person gender to male
    When I POST to "/api/v1/persons" with body:
      """
      {"fullName": "Gender Switch Person", "gender": "Female"}
      """
    Then the response status is 201
    When I PATCH the created person with body:
      """
      {"gender": "Male"}
      """
    Then the response status is 200
    And the response body contains "Gender Switch Person"

  Scenario: Update person userIdentifier
    When I POST to "/api/v1/persons" with body:
      """
      {"fullName": "Identifier Person", "gender": "Male"}
      """
    Then the response status is 201
    When I PATCH the created person with body:
      """
      {"userIdentifier": "identifier-test-123"}
      """
    Then the response status is 200
    And the response body contains "identifier-test-123"

  Scenario: Create person with duplicate userIdentifier returns 409
    When I POST to "/api/v1/persons" with body:
      """
      {"fullName": "First Person", "gender": "Male", "userIdentifier": "shared-uid-abc"}
      """
    Then the response status is 201
    When I POST to "/api/v1/persons" with body:
      """
      {"fullName": "Second Person", "gender": "Female", "userIdentifier": "shared-uid-abc"}
      """
    Then the response status is 409

  Scenario: Delete person with document dependency returns 409
    Given a person with a document exists for delete constraint tests
    When I DELETE the person with document dependency
    Then the response status is 409

  Scenario: Delete person with household membership returns 409
    Given a person with household membership exists for delete constraint tests
    When I DELETE the person with household dependency
    Then the response status is 409
