package com.example.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.admin.api.AdminDtos;
import com.example.admin.application.AdminContentService;
import com.example.common.ApiException;
import com.example.content.domain.AnswerOptionEntity;
import com.example.content.domain.DomainEntity;
import com.example.content.domain.QuestionEntity;
import com.example.scan.api.ScanDtos;
import com.example.scan.application.ScanService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class RequiredDomainTest {
  @Inject AdminContentService admin;
  @Inject ScanService scans;

  private DomainEntity domain() {
    DomainEntity domain = new DomainEntity();
    domain.id = UUID.randomUUID();
    domain.name = "Verplicht testdomein";
    domain.active = true;
    domain.required = true;
    domain.persist();
    return domain;
  }

  private QuestionEntity question(DomainEntity domain) {
    QuestionEntity question = new QuestionEntity();
    question.id = UUID.randomUUID();
    question.domainId = domain.id;
    question.title = "Testvraag";
    question.text = "Vraagtekst";
    question.active = true;
    question.persist();
    AnswerOptionEntity option = new AnswerOptionEntity();
    option.id = UUID.randomUUID();
    option.questionId = question.id;
    option.label = "Optie";
    option.available = true;
    option.persist();
    DomainEntity.getEntityManager().flush();
    return question;
  }

  private void update(QuestionEntity question, UUID domainId, boolean active) {
    admin.updateQuestion(null, question.id, new AdminDtos.QuestionUpdate(domainId, question.title,
        question.text, null, 0, active, question.version));
  }

  @Test
  @TestTransaction
  void referenceDomainsAreRequiredAndNewDomainsDefaultToOptional() {
    assertThat(DomainEntity.<DomainEntity>list("required = true").stream().map(item -> item.name))
        .contains("Datastrategie", "Datakwaliteit", "Governance", "Technologie", "Processen", "Vaardigheden",
            "Datagedreven besluitvorming");
    var created = admin.createDomain(null, UUID.randomUUID().toString(),
        new AdminDtos.DomainWrite("Optioneel", null, 1000, true, 0L));
    assertThat(created.required()).isFalse();
  }

  @Test
  @TestTransaction
  void requiredDomainCannotBeDeactivated() {
    DomainEntity domain = domain();
    assertThatThrownBy(() -> admin.updateDomain(null, domain.id,
        new AdminDtos.DomainWrite(domain.name, null, 0, false, domain.version)))
        .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(422));
  }

  @Test
  @TestTransaction
  void cannotDeactivateLastUsableQuestion() {
    QuestionEntity question = question(domain());
    assertThatThrownBy(() -> update(question, question.domainId, false))
        .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(422));
  }

  @Test
  @TestTransaction
  void cannotMoveLastUsableQuestion() {
    QuestionEntity question = question(domain());
    DomainEntity target = domain();
    assertThatThrownBy(() -> update(question, target.id, true))
        .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(422));
  }

  @Test
  @TestTransaction
  void cannotDisableLastOption() {
    QuestionEntity question = question(domain());
    AnswerOptionEntity option = AnswerOptionEntity.find("questionId", question.id).firstResult();
    assertThatThrownBy(() -> admin.updateOption(null, option.id,
        new AdminDtos.AnswerOptionWrite(0, "Optie", null, 0, false, option.version)))
        .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(422));
  }

  @Test
  @TestTransaction
  void anotherUsableQuestionAllowsDeactivation() {
    DomainEntity domain = domain();
    QuestionEntity first = question(domain);
    question(domain);
    update(first, domain.id, false);
    assertThat(first.active).isFalse();
  }

  @Test
  @TestTransaction
  void anotherUsableQuestionAllowsMoving() {
    DomainEntity source = domain();
    QuestionEntity first = question(source);
    question(source);
    DomainEntity target = domain();
    update(first, target.id, true);
    assertThat(first.domainId).isEqualTo(target.id);
  }

  @Test
  @TestTransaction
  void questionWithoutAvailableOptionDoesNotCountAsRemainingContent() {
    DomainEntity domain = domain();
    QuestionEntity first = question(domain);
    QuestionEntity unusable = question(domain);
    AnswerOptionEntity.update("available = false where questionId = ?1", unusable.id);
    assertThatThrownBy(() -> update(first, domain.id, false))
        .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(422));
  }

  @Test
  @TestTransaction
  void scanCreationChecksRequiredContentEvenWhenDatabaseWasChangedDirectly() {
    domain(); // Geen vragen: omzeilt bewust de beheerlaag.
    assertThatThrownBy(() -> scans.create(
        UUID.fromString("90000000-0000-0000-0000-000000000001"),
        UUID.fromString("90000000-0000-0000-0000-000000000010"), UUID.randomUUID().toString(),
        new ScanDtos.ScanCreateInput("Testscan", "2026", List.of())))
        .isInstanceOfSatisfying(ApiException.class, error -> {
          assertThat(error.status()).isEqualTo(422);
          assertThat(error.title()).isEqualTo("Beoordelingsmodel onvolledig");
        });
  }
}
