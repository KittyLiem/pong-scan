package com.example.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.account.api.AccountDtos;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CredentialLimitsTest {
  @Inject Validator validator;

  @Test
  void registrationRejectsCredentialsOver128Characters() {
    assertThat(validator.validate(new AccountDtos.RegistrationInput("test@example.nl", "x".repeat(129))))
        .anyMatch(error -> error.getPropertyPath().toString().equals("credential"));
  }

  @Test
  void sessionRejectsCredentialsOver128Characters() {
    assertThat(validator.validate(new AccountDtos.SessionInput("test@example.nl", "x".repeat(129), null)))
        .anyMatch(error -> error.getPropertyPath().toString().equals("credential"));
    assertThat(validator.validate(new AccountDtos.SessionInput("test@example.nl", "x".repeat(128), null))).isEmpty();
  }

  @Test
  void registrationStillRequiresEightCharacters() {
    assertThat(validator.validate(new AccountDtos.RegistrationInput("test@example.nl", "x".repeat(7))))
        .anyMatch(error -> error.getPropertyPath().toString().equals("credential"));
  }
}
