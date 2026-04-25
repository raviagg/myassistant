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
