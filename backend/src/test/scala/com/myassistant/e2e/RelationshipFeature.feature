Feature: Relationship Management

  Background:
    Given the server is running and I am authenticated

  Scenario: Create a relationship between two persons
    Given two persons exist for relationship tests
    When I create a "father" relationship from person A to person B
    Then the response status is 201
    And the response body contains "father"

  Scenario: Get an existing relationship
    Given two persons exist for relationship tests
    When I create a "mother" relationship from person A to person B
    Then the response status is 201
    When I GET the relationship between person A and person B
    Then the response status is 200
    And the response body contains "mother"

  Scenario: Get non-existent relationship returns 404
    Given two persons exist for relationship tests
    When I GET the relationship between person A and person B
    Then the response status is 404

  Scenario: List relationships for a person
    Given two persons exist for relationship tests
    When I create a "son" relationship from person A to person B
    Then the response status is 201
    When I GET relationships for person A
    Then the response status is 200
    And the response body contains "son"

  Scenario: Update a relationship type
    Given two persons exist for relationship tests
    When I create a "brother" relationship from person A to person B
    Then the response status is 201
    When I PATCH the relationship between person A and person B with type "sister"
    Then the response status is 200
    And the response body contains "sister"

  Scenario: Delete a relationship
    Given two persons exist for relationship tests
    When I create a "husband" relationship from person A to person B
    Then the response status is 201
    When I DELETE the relationship between person A and person B
    Then the response status is 204

  Scenario: Resolve direct kinship
    Given two persons exist for relationship tests
    When I create a "father" relationship from person A to person B
    Then the response status is 201
    When I GET kinship between person A and person B
    Then the response status is 200
    And the response body contains "father"

  Scenario: Create daughter and wife relationships
    Given two persons exist for relationship tests
    When I create a "daughter" relationship from person A to person B
    Then the response status is 201
    And the response body contains "daughter"
    Given two persons exist for relationship tests
    When I create a "wife" relationship from person A to person B
    Then the response status is 201
    And the response body contains "wife"

  Scenario: Create and retrieve a husband relationship
    Given two persons exist for relationship tests
    When I create a "husband" relationship from person A to person B
    Then the response status is 201
    When I GET the relationship between person A and person B
    Then the response status is 200
    And the response body contains "husband"

  Scenario: Kinship returns 404 when persons are not connected
    Given two persons exist for relationship tests
    When I GET kinship between person A and person B
    Then the response status is 404

  Scenario: Kinship resolves via reverse edge traversal
    Given two persons exist for relationship tests
    When I create a "father" relationship from person A to person B
    Then the response status is 201
    When I GET kinship between person B and person A
    Then the response status is 200

  Scenario: Multi-hop kinship traversal
    Given three persons exist for kinship chain tests
    When I create a "father" relationship from person A to person B
    Then the response status is 201
    When I create a "father" relationship from person B to person C
    Then the response status is 201
    When I GET kinship between person A and person C
    Then the response status is 200
    And the response body contains "father"

  Scenario: Multi-hop kinship with different relation types
    Given three persons exist for kinship chain tests
    When I create a "mother" relationship from person A to person B
    Then the response status is 201
    When I create a "son" relationship from person B to person C
    Then the response status is 201
    When I GET kinship between person A and person C
    Then the response status is 200
    And the response body contains "son"

  Scenario: Create self-relationship returns 422
    Given two persons exist for relationship tests
    When I create a self-relationship for person A
    Then the response status is 422

  Scenario: Kinship from person to themselves returns 404
    Given two persons exist for relationship tests
    When I GET kinship from person A to themselves
    Then the response status is 404

  Scenario: Delete non-existent relationship returns 404
    Given two persons exist for relationship tests
    When I DELETE the relationship between person A and person B
    Then the response status is 404

  Scenario: Create duplicate relationship returns 409
    Given two persons exist for relationship tests
    When I create a "father" relationship from person A to person B
    Then the response status is 201
    When I create a "father" relationship from person A to person B
    Then the response status is 409

  Scenario: Create relationship with non-existent persons returns 409
    When I POST to "/api/v1/relationships" with body:
      """
      {"fromPersonId":"00000000-0000-0000-0000-000000000001","toPersonId":"00000000-0000-0000-0000-000000000002","relationType":"father"}
      """
    Then the response status is 409

  Scenario: Kinship resolves via daughter relationship
    Given two persons exist for relationship tests
    When I create a "daughter" relationship from person A to person B
    Then the response status is 201
    When I GET kinship between person A and person B
    Then the response status is 200
    And the response body contains "daughter"

  Scenario: Kinship resolves via brother relationship
    Given two persons exist for relationship tests
    When I create a "brother" relationship from person A to person B
    Then the response status is 201
    When I GET kinship between person A and person B
    Then the response status is 200
    And the response body contains "brother"

  Scenario: Kinship resolves via sister relationship
    Given two persons exist for relationship tests
    When I create a "sister" relationship from person A to person B
    Then the response status is 201
    When I GET kinship between person A and person B
    Then the response status is 200
    And the response body contains "sister"

  Scenario: Kinship resolves via husband relationship
    Given two persons exist for relationship tests
    When I create a "husband" relationship from person A to person B
    Then the response status is 201
    When I GET kinship between person A and person B
    Then the response status is 200
    And the response body contains "husband"

  Scenario: Kinship resolves via wife relationship
    Given two persons exist for relationship tests
    When I create a "wife" relationship from person A to person B
    Then the response status is 201
    When I GET kinship between person A and person B
    Then the response status is 200
    And the response body contains "wife"
