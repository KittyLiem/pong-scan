package com.example.admin.application;

import com.example.admin.api.AdminDtos;
import com.example.audit.AuditService;
import com.example.common.ApiException;
import com.example.common.Cursors;
import com.example.common.Hashing;
import com.example.common.Texts;
import com.example.content.domain.AnswerOptionEntity;
import com.example.content.domain.DomainEntity;
import com.example.content.domain.QuestionEntity;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Beheer van de inhoud. Wijzigingen raken alleen nieuwe scans (bestaande scans gebruiken hun snapshot).
 * Er wordt niets fysiek verwijderd. Een actieve vraag heeft altijd minimaal één beschikbare optie.
 */
@ApplicationScoped
public class AdminContentService {
  private final AuditService audit;

  public AdminContentService(AuditService audit) {
    this.audit = audit;
  }

  @Transactional
  public AdminDtos.DomainCollection listDomains(String cursor, Integer pageSize) {
    int size = Cursors.pageSize(pageSize);
    int start = Cursors.decode(cursor);
    List<DomainEntity> domains = DomainEntity.find("order by displayOrder, id").range(start, start + size).list();
    boolean hasNext = domains.size() > size;
    List<DomainEntity> page = domains.stream().limit(size).toList();
    if (page.isEmpty()) return new AdminDtos.DomainCollection(List.of(), null);

    List<UUID> domainIds = page.stream().map(domain -> domain.id).toList();
    List<QuestionEntity> questions = QuestionEntity.list("domainId in ?1 order by displayOrder, id", domainIds);
    Map<UUID, List<AnswerOptionEntity>> optionsByQuestion = new HashMap<>();
    if (!questions.isEmpty()) {
      List<UUID> questionIds = questions.stream().map(question -> question.id).toList();
      AnswerOptionEntity.<AnswerOptionEntity>list("questionId in ?1 order by displayOrder, score, id", questionIds)
          .forEach(option -> optionsByQuestion.computeIfAbsent(option.questionId, key -> new ArrayList<>()).add(option));
    }
    Map<UUID, List<AdminDtos.Question>> questionsByDomain = new HashMap<>();
    questions.forEach(question -> questionsByDomain.computeIfAbsent(question.domainId, key -> new ArrayList<>())
        .add(questionDto(question, optionsByQuestion.getOrDefault(question.id, List.of()))));
    List<AdminDtos.Domain> items = page.stream()
        .map(domain -> domainDto(domain, questionsByDomain.getOrDefault(domain.id, List.of())))
        .toList();
    return new AdminDtos.DomainCollection(items, hasNext ? Cursors.encode(start + size) : null);
  }

  @Transactional
  public AdminDtos.Domain createDomain(UUID actor, String idempotencyKey, AdminDtos.DomainWrite input) {
    requireInitialVersion(input.version());
    String keyHash = Hashing.requireIdempotencyKey(idempotencyKey);
    String fingerprint = Hashing.fingerprint("domain", input);
    DomainEntity replay = DomainEntity.find("idempotencyKeyHash", keyHash).firstResult();
    if (replay != null) {
      ensureSameRequest(replay.requestFingerprint, fingerprint);
      return domainDto(replay, List.of());
    }
    DomainEntity domain = new DomainEntity();
    domain.id = UUID.randomUUID();
    apply(domain, input);
    domain.idempotencyKeyHash = keyHash;
    domain.requestFingerprint = fingerprint;
    domain.persist();
    flush();
    audit.success(actor, "admin.domain.create", "domain", domain.id, null);
    return domainDto(domain, List.of());
  }

  @Transactional
  public AdminDtos.Domain updateDomain(UUID actor, UUID domainId, AdminDtos.DomainWrite input) {
    DomainEntity domain = DomainEntity.<DomainEntity>findByIdOptional(domainId)
        .orElseThrow(() -> ApiException.notFound("Kennisgebied niet gevonden."));
    requireVersion(domain.version, input.version());
    if (domain.required && !input.active()) {
      throw ApiException.unprocessable("Verplicht kennisgebied", "Een verplicht kennisgebied kan niet worden gedeactiveerd.");
    }
    apply(domain, input);
    flush();
    audit.success(actor, "admin.domain.update", "domain", domain.id, null);
    return domainDto(domain, questionsOf(domain.id));
  }

  @Transactional
  public AdminDtos.Question createQuestion(UUID actor, UUID domainId, String idempotencyKey,
      AdminDtos.QuestionCreate input) {
    requireInitialVersion(input.version());
    DomainEntity domain = DomainEntity.<DomainEntity>findByIdOptional(domainId)
        .orElseThrow(() -> ApiException.notFound("Kennisgebied niet gevonden."));
    String keyHash = Hashing.requireIdempotencyKey(idempotencyKey);
    String fingerprint = Hashing.fingerprint("question", domainId, input);
    QuestionEntity replay = QuestionEntity.find("idempotencyKeyHash", keyHash).firstResult();
    if (replay != null) {
      ensureSameRequest(replay.requestFingerprint, fingerprint);
      return questionDto(replay, optionsOf(replay.id));
    }
    if (Boolean.TRUE.equals(input.active())) {
      throw activeWithoutOption();
    }
    QuestionEntity question = new QuestionEntity();
    question.id = UUID.randomUUID();
    question.domainId = domain.id;
    question.title = input.title().trim();
    question.text = input.text().trim();
    question.explanation = Texts.blankToNull(input.explanation());
    question.displayOrder = input.order();
    question.active = false;
    question.idempotencyKeyHash = keyHash;
    question.requestFingerprint = fingerprint;
    question.persist();
    flush();
    audit.success(actor, "admin.question.create", "question", question.id, null);
    return questionDto(question, List.of());
  }

  @Transactional
  public AdminDtos.Question updateQuestion(UUID actor, UUID questionId, AdminDtos.QuestionUpdate input) {
    QuestionEntity question = lockQuestion(questionId);
    if (DomainEntity.findById(input.domainId()) == null) {
      throw ApiException.unprocessable("Onbekend kennisgebied", "Het opgegeven kennisgebied bestaat niet.");
    }
    requireVersion(question.version, input.version());
    if (Boolean.TRUE.equals(input.active()) && availableOptions(question.id) == 0) {
      throw activeWithoutOption();
    }
    if (question.active && (!input.active() || !question.domainId.equals(input.domainId()))) {
      requireRemainingContent(question);
    }
    question.domainId = input.domainId();
    question.title = input.title().trim();
    question.text = input.text().trim();
    question.explanation = Texts.blankToNull(input.explanation());
    question.displayOrder = input.order();
    question.active = input.active();
    flush();
    audit.success(actor, "admin.question.update", "question", question.id, null);
    return questionDto(question, optionsOf(question.id));
  }

  @Transactional
  public AdminDtos.AnswerOption createOption(UUID actor, UUID questionId, String idempotencyKey,
      AdminDtos.AnswerOptionWrite input) {
    requireInitialVersion(input.version());
    QuestionEntity question = lockQuestion(questionId);
    String keyHash = Hashing.requireIdempotencyKey(idempotencyKey);
    String fingerprint = Hashing.fingerprint("option", questionId, input);
    AnswerOptionEntity replay = AnswerOptionEntity.find("idempotencyKeyHash", keyHash).firstResult();
    if (replay != null) {
      ensureSameRequest(replay.requestFingerprint, fingerprint);
      return optionDto(replay);
    }
    AnswerOptionEntity option = new AnswerOptionEntity();
    option.id = UUID.randomUUID();
    option.questionId = question.id;
    apply(option, input);
    option.idempotencyKeyHash = keyHash;
    option.requestFingerprint = fingerprint;
    option.persist();
    flush();
    audit.success(actor, "admin.option.create", "answer-option", option.id, null);
    return optionDto(option);
  }

  @Transactional
  public AdminDtos.AnswerOption updateOption(UUID actor, UUID optionId, AdminDtos.AnswerOptionWrite input) {
    AnswerOptionEntity option = AnswerOptionEntity.<AnswerOptionEntity>findByIdOptional(optionId)
        .orElseThrow(() -> ApiException.notFound("Antwoordoptie niet gevonden."));
    // Eerst de bovenliggende vraag locken, daarna de optie verversen: zo ziet de invariantcontrole de actuele
    // toestand en kunnen gelijktijdige wijzigingen de vraag niet zonder beschikbare optie achterlaten.
    QuestionEntity question = lockQuestion(option.questionId);
    Panache.getEntityManager().refresh(option, LockModeType.PESSIMISTIC_WRITE);
    requireVersion(option.version, input.version());
    if (option.available && !input.available()) {
      if (question.active && availableOptions(question.id) <= 1) {
        requireRemainingContent(question);
        throw ApiException.unprocessable("Laatste beschikbare optie",
            "Een actieve vraag moet minimaal één beschikbare antwoordoptie houden. Zet eerst de vraag op inactief.");
      }
    }
    apply(option, input);
    flush();
    audit.success(actor, "admin.option.update", "answer-option", option.id, null);
    return optionDto(option);
  }

  // ------------------------------------------------------------------ helpers

  private static void apply(DomainEntity domain, AdminDtos.DomainWrite input) {
    domain.name = input.name().trim();
    domain.description = Texts.blankToNull(input.description());
    domain.displayOrder = input.order();
    domain.active = input.active();
  }

  private static void apply(AnswerOptionEntity option, AdminDtos.AnswerOptionWrite input) {
    option.score = input.score();
    option.label = input.label().trim();
    option.description = Texts.blankToNull(input.description());
    option.displayOrder = input.order();
    option.available = input.available();
  }

  /** Eerst het domein en daarna de vraag vergrendelen: ook de domeininvariant wordt geserialiseerd. */
  private static QuestionEntity lockQuestion(UUID questionId) {
    QuestionEntity question = QuestionEntity.<QuestionEntity>findByIdOptional(questionId)
        .orElseThrow(() -> ApiException.notFound("Vraag niet gevonden."));
    DomainEntity domain = DomainEntity.findById(question.domainId);
    Panache.getEntityManager().refresh(domain, LockModeType.PESSIMISTIC_WRITE);
    Panache.getEntityManager().refresh(question, LockModeType.PESSIMISTIC_WRITE);
    if (!question.domainId.equals(domain.id)) throw ApiException.versionConflict();
    return question;
  }

  private static void requireRemainingContent(QuestionEntity question) {
    DomainEntity domain = DomainEntity.findById(question.domainId);
    if (domain.required && QuestionEntity.count("from QuestionEntity q where q.domainId = ?1 and q.active = true and q.id <> ?2 "
        + "and exists (select o.id from AnswerOptionEntity o where o.questionId = q.id and o.available = true)",
        question.domainId, question.id) == 0) {
      throw ApiException.unprocessable("Verplicht kennisgebied zonder inhoud",
          "Een verplicht kennisgebied moet minimaal één actieve vraag met een beschikbare antwoordoptie houden.");
    }
  }

  private static long availableOptions(UUID questionId) {
    return AnswerOptionEntity.count("questionId = ?1 and available = true", questionId);
  }

  private static List<AnswerOptionEntity> optionsOf(UUID questionId) {
    return AnswerOptionEntity.list("questionId = ?1 order by displayOrder, score, id", questionId);
  }

  private static List<AdminDtos.Question> questionsOf(UUID domainId) {
    List<QuestionEntity> questions = QuestionEntity.list("domainId = ?1 order by displayOrder, id", domainId);
    if (questions.isEmpty()) return List.of();
    Map<UUID, List<AnswerOptionEntity>> options = new HashMap<>();
    AnswerOptionEntity.<AnswerOptionEntity>list("questionId in ?1 order by displayOrder, score, id",
            questions.stream().map(question -> question.id).toList())
        .forEach(option -> options.computeIfAbsent(option.questionId, key -> new ArrayList<>()).add(option));
    return questions.stream().map(question -> questionDto(question, options.getOrDefault(question.id, List.of())))
        .toList();
  }

  private static AdminDtos.Domain domainDto(DomainEntity domain, List<AdminDtos.Question> questions) {
    return new AdminDtos.Domain(domain.id, domain.name, domain.description, domain.displayOrder, domain.active, domain.required,
        domain.version, questions);
  }

  private static AdminDtos.Question questionDto(QuestionEntity question, List<AnswerOptionEntity> options) {
    return new AdminDtos.Question(question.id, question.domainId, question.title, question.text,
        question.explanation, question.displayOrder, question.active, question.version,
        options.stream().map(AdminContentService::optionDto).toList());
  }

  private static AdminDtos.AnswerOption optionDto(AnswerOptionEntity option) {
    return new AdminDtos.AnswerOption(option.id, option.questionId, option.score, option.label, option.description,
        option.displayOrder, option.available, option.version);
  }

  private static ApiException activeWithoutOption() {
    return ApiException.unprocessable("Vraag zonder beschikbare optie",
        "Een vraag kan pas actief worden als er minimaal één beschikbare antwoordoptie is.");
  }

  private static void requireInitialVersion(long version) {
    if (version != 0) {
      throw ApiException.conflict("Versieconflict", "Nieuwe inhoud moet met versie 0 worden aangemaakt.");
    }
  }

  private static void requireVersion(long actual, long supplied) {
    if (actual != supplied) throw ApiException.versionConflict();
  }

  private static void ensureSameRequest(String actual, String expected) {
    if (!Hashing.constantTimeEquals(actual, expected)) throw ApiException.idempotencyConflict();
  }

  private static void flush() {
    Panache.getEntityManager().flush();
  }
}
