Feature: Household Management

  Background:
    Given the server is running and I am authenticated

  Scenario: Create and retrieve a household
    When I POST to "/api/v1/households" with body:
      """
      {"name": "Aggarwal Family"}
      """
    Then the response status is 201
    And the response body contains "Aggarwal Family"
    When I GET the created household by id
    Then the response status is 200
    And the response body contains "Aggarwal Family"

  Scenario: Get non-existent household returns 404
    When I GET "/api/v1/households/00000000-0000-0000-0000-000000000000"
    Then the response status is 404

  Scenario: List households
    When I POST to "/api/v1/households" with body:
      """
      {"name": "Listed Household"}
      """
    Then the response status is 201
    When I GET "/api/v1/households"
    Then the response status is 200
    And the response body contains "Listed Household"

  Scenario: Create household requires name
    When I POST to "/api/v1/households" with body:
      """
      {}
      """
    Then the response status is 400

  Scenario: Update a household
    When I POST to "/api/v1/households" with body:
      """
      {"name": "Original Name"}
      """
    Then the response status is 201
    When I PATCH the created household with body:
      """
      {"name": "Renamed Household"}
      """
    Then the response status is 200
    And the response body contains "Renamed Household"

  Scenario: Delete a household
    When I POST to "/api/v1/households" with body:
      """
      {"name": "To Be Deleted"}
      """
    Then the response status is 201
    When I DELETE the created household by id
    Then the response status is 204

  Scenario: Add and list household members
    Given a person and household exist for membership tests
    When I add the person to the household
    Then the response status is 204
    When I GET members of the household
    Then the response status is 200
    And the household member list contains the person id

  Scenario: Remove a household member
    Given a person and household exist for membership tests
    When I add the person to the household
    Then the response status is 204
    When I remove the person from the household
    Then the response status is 204

  Scenario: List households for a person
    Given a person and household exist for membership tests
    When I add the person to the household
    Then the response status is 204
    When I GET households for the person
    Then the response status is 200
    And the household list contains the household id

  Scenario: Add duplicate household member returns 409
    Given a person and household exist for membership tests
    When I add the person to the household
    Then the response status is 204
    When I add the person to the household
    Then the response status is 409

  Scenario: Update household with empty patch returns unchanged household
    When I POST to "/api/v1/households" with body:
      """
      {"name": "Stable Household"}
      """
    Then the response status is 201
    When I PATCH the created household with body:
      """
      {}
      """
    Then the response status is 200
    And the response body contains "Stable Household"

  Scenario: Delete household with members returns 409
    Given a person and household exist for membership tests
    When I add the person to the household
    Then the response status is 204
    When I DELETE the created household by id
    Then the response status is 409

  Scenario: Update non-existent household returns 404
    When I PATCH "/api/v1/households/00000000-0000-0000-0000-000000000000" with body:
      """
      {"name": "Ghost Household"}
      """
    Then the response status is 404

  Scenario: Delete non-existent household returns 404
    When I DELETE "/api/v1/households/00000000-0000-0000-0000-000000000000"
    Then the response status is 404

  Scenario: Delete household with document returns 409
    Given a household with a document exists for delete constraint tests
    When I DELETE the household with document dependency
    Then the response status is 409

  Scenario: Add non-existent person to household returns 409
    When I POST to "/api/v1/households" with body:
      """
      {"name": "FK Test Household"}
      """
    Then the response status is 201
    When I add a non-existent person to the created household
    Then the response status is 409
