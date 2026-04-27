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
