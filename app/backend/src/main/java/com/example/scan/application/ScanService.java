package com.example.scan.application;

import com.example.audit.AuditService;
import com.example.common.ApiException;
import com.example.common.Cursors;
import com.example.common.Hashing;
import com.example.common.Problem;
import com.example.common.Texts;
import com.example.content.application.ActiveContentService;
import com.example.content.domain.DomainEntity;
import com.example.content.domain.AnswerOptionEntity;
import com.example.organization.domain.OrganizationEntity;
import com.example.scan.api.ScanDtos;
import com.example.scan.domain.AnswerEntity;
import com.example.scan.domain.ScanEntity;
import com.example.scan.domain.SnapshotDomainEntity;
import com.example.scan.domain.SnapshotOptionEntity;
import com.example.scan.domain.SnapshotQuestionEntity;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ScanService {
  @ConfigProperty(name = "scan.quota.max-per-organization", defaultValue = "50")
  int maxPerOrganization;
  @ConfigProperty(name = "scan.quota.max-per-day", defaultValue = "20")
  int maxPerDay;

  private final ScanAccess access;
  private final ScanState state;
  private final ActiveContentService activeContent;
  private final AuditService audit;

  public ScanService(ScanAccess access, ScanState state, ActiveContentService activeContent, AuditService audit) {
    this.access = access;
    this.state = state;
    this.activeContent = activeContent;
    this.audit = audit;
  }

  // ------------------------------------------------------------------ aanmaken

  @Transactional
  public ScanDtos.Scan create(UUID memberId, UUID organizationId, String idempotencyKey,
      ScanDtos.ScanCreateInput input) {
    lockOrganization(memberId, organizationId);
    String keyHash = Hashing.requireIdempotencyKey(idempotencyKey);
    List<UUID> initial = input.initialSelectedDomainIds() == null
        ? List.of() : List.copyOf(new LinkedHashSet<>(input.initialSelectedDomainIds()));
    String name = input.name().trim();
    String period = input.assessmentPeriod().trim();
    String fingerprint = Hashing.fingerprint("create", name, period,
        initial.stream().map(UUID::toString).sorted().toList());

    var replay = ScanEntity.byOrganizationAndIdempotencyKey(organizationId, keyHash);
    if (replay.isPresent()) {
      if (!Hashing.constantTimeEquals(replay.get().requestFingerprint, fingerprint)) {
        throw ApiException.idempotencyConflict();
      }
      return toResponse(replay.get());
    }

    checkQuota(organizationId);
    List<ActiveContentService.ActiveDomain> content = activeContent.load();
    Set<UUID> activeDomainIds = new HashSet<>();
    content.forEach(item -> activeDomainIds.add(item.domain().id));
    if (DomainEntity.<DomainEntity>list("required = true")
        .stream().anyMatch(domain -> !activeDomainIds.contains(domain.id))) {
      throw ApiException.unprocessable("Beoordelingsmodel onvolledig",
          "Ieder verplicht kennisgebied moet actieve inhoud met een beschikbare antwoordoptie bevatten.");
    }
    if (content.isEmpty()) {
      throw ApiException.unprocessable("Geen actieve inhoud",
          "Er is geen actieve inhoud (kennisgebied met een actieve vraag en beschikbare optie) om een scan mee te starten.");
    }
    List<UUID> unknown = initial.stream().filter(id -> !activeDomainIds.contains(id)).toList();
    if (!unknown.isEmpty()) {
      throw new ApiException(422, "Ongeldige selectie",
          "Een of meer gekozen kennisgebieden zijn niet (meer) actief.",
          List.of(new Problem.FieldError("initialSelectedDomainIds", "bevat een onbekend of inactief kennisgebied")));
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    ScanEntity scan = new ScanEntity();
    scan.id = UUID.randomUUID();
    scan.organizationId = organizationId;
    scan.name = name;
    scan.assessmentPeriod = period;
    scan.status = ScanEntity.CONCEPT;
    scan.progress = 0;
    scan.revision = 1;
    scan.idempotencyKeyHash = keyHash;
    scan.requestFingerprint = fingerprint;
    scan.createdAt = now;
    scan.updatedAt = now;
    scan.persist();
    Panache.getEntityManager().flush();

    Map<UUID, UUID> snapshotBySource = snapshot(scan.id, content);
    initial.forEach(id -> scan.selectedDomainIds.add(snapshotBySource.get(id)));
    state.apply(scan);
    Panache.getEntityManager().flush();
    audit.success(memberId, "scan.create", "scan", scan.id, organizationId);
    return toResponse(scan);
  }

  /** Kopieert de actieve inhoud naar de snapshottabellen; geeft bron-domein-id → snapshot-domein-id. */
  private Map<UUID, UUID> snapshot(UUID scanId, List<ActiveContentService.ActiveDomain> content) {
    var em = Panache.getEntityManager();
    Map<UUID, UUID> domainMap = new HashMap<>();
    List<SnapshotQuestionEntity> questions = new ArrayList<>();
    List<SnapshotOptionEntity> options = new ArrayList<>();
    for (ActiveContentService.ActiveDomain item : content) {
      SnapshotDomainEntity domain = new SnapshotDomainEntity();
      domain.id = UUID.randomUUID();
      domain.scanId = scanId;
      domain.sourceId = item.domain().id;
      domain.name = item.domain().name;
      domain.description = item.domain().description;
      domain.displayOrder = item.domain().displayOrder;
      em.persist(domain);
      domainMap.put(item.domain().id, domain.id);
      for (ActiveContentService.ActiveQuestion activeQuestion : item.questions()) {
        SnapshotQuestionEntity question = new SnapshotQuestionEntity();
        question.id = UUID.randomUUID();
        question.scanId = scanId;
        question.snapshotDomainId = domain.id;
        question.sourceId = activeQuestion.question().id;
        question.title = activeQuestion.question().title;
        question.text = activeQuestion.question().text;
        question.explanation = activeQuestion.question().explanation;
        question.displayOrder = activeQuestion.question().displayOrder;
        questions.add(question);
        for (AnswerOptionEntity source : activeQuestion.options()) {
          SnapshotOptionEntity option = new SnapshotOptionEntity();
          option.id = UUID.randomUUID();
          option.scanId = scanId;
          option.snapshotQuestionId = question.id;
          option.sourceId = source.id;
          option.score = source.score;
          option.label = source.label;
          option.description = source.description;
          option.displayOrder = source.displayOrder;
          options.add(option);
        }
      }
    }
    em.flush();
    questions.forEach(em::persist);
    em.flush();
    options.forEach(em::persist);
    em.flush();
    return domainMap;
  }

  // ------------------------------------------------------------------ lezen

  @Transactional
  public ScanDtos.ScanCollection list(UUID memberId, UUID organizationId, String cursor, Integer pageSize) {
    access.requireOrganization(memberId, organizationId);
    int size = Cursors.pageSize(pageSize);
    int start = Cursors.decode(cursor);
    List<ScanEntity> entities = ScanEntity
        .find("organizationId = ?1 order by updatedAt desc, id", organizationId)
        .range(start, start + size)
        .list();
    boolean hasNext = entities.size() > size;
    List<ScanEntity> page = entities.stream().limit(size).toList();
    return new ScanDtos.ScanCollection(toResponses(page), hasNext ? Cursors.encode(start + size) : null);
  }

  @Transactional
  public ScanDtos.Scan get(UUID memberId, UUID scanId) {
    return toResponse(access.load(memberId, scanId));
  }

  // ------------------------------------------------------------------ wijzigen

  @Transactional
  public ScanDtos.Scan update(UUID memberId, UUID scanId, ScanDtos.ScanUpdateInput input) {
    ScanEntity scan = access.lock(memberId, scanId);
    if (scan.isCompleted()) {
      throw ApiException.conflict("Scan is afgerond",
          "Een afgeronde scan kan niet meer worden gewijzigd. Maak een revisie om verder te werken.");
    }
    if (scan.version != input.version()) {
      throw ApiException.versionConflict();
    }
    if (input.name() != null) {
      if (input.name().isBlank()) {
        throw new ApiException(400, "Ongeldige invoer", "Een of meer velden zijn ontbrekend of ongeldig.",
            List.of(new Problem.FieldError("name", "is verplicht")));
      }
      scan.name = input.name().trim();
    }
    if (input.assessmentPeriod() != null) {
      scan.assessmentPeriod = input.assessmentPeriod().trim();
    }
    if (input.selectedDomainIds() != null) {
      Set<UUID> selected = new LinkedHashSet<>(input.selectedDomainIds());
      long known = SnapshotDomainEntity.count("scanId = ?1 and id in ?2", scan.id, selected);
      if (known != selected.size()) {
        throw new ApiException(422, "Ongeldige selectie", "Een of meer kennisgebieden horen niet bij deze scan.",
            List.of(new Problem.FieldError("selectedDomainIds", "bevat een kennisgebied dat niet bij deze scan hoort")));
      }
      scan.selectedDomainIds.retainAll(selected);
      scan.selectedDomainIds.addAll(selected);
    }
    if (input.goals() != null) {
      scan.goals.clear();
      input.goals().forEach(goal -> scan.goals.add(new ScanEntity.Goal(goal.name().trim(), goal.priority(),
          Texts.blankToNull(goal.customText()))));
    }
    state.apply(scan);
    scan.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    Panache.getEntityManager().flush();
    return toResponse(scan);
  }

  @Transactional
  public ScanDtos.Scan complete(UUID memberId, UUID scanId, String idempotencyKey, ScanDtos.CompletionInput input) {
    ScanEntity scan = access.lock(memberId, scanId);
    String comments = Texts.blankToNull(input.generalComments());
    String keyHash = idempotencyKey == null || idempotencyKey.isBlank()
        ? null : Hashing.requireIdempotencyKey(idempotencyKey);
    String fingerprint = Hashing.fingerprint("complete", input.version(), comments);
    if (keyHash != null && Hashing.constantTimeEquals(scan.completionIdempotencyKeyHash, keyHash)) {
      if (!Hashing.constantTimeEquals(scan.completionRequestFingerprint, fingerprint)) {
        throw ApiException.idempotencyConflict();
      }
      return toResponse(scan);
    }
    if (scan.isCompleted()) {
      throw ApiException.conflict("Scan is al afgerond", "Deze scan is al afgerond.");
    }
    if (scan.version != input.version()) {
      throw ApiException.versionConflict();
    }
    if (scan.selectedDomainIds.isEmpty()) {
      throw ApiException.unprocessable("Beoordeling niet compleet",
          "Selecteer minimaal één kennisgebied voordat u de scan afrondt.");
    }
    ScanState.Counts counts = state.counts(scan.id, scan.selectedDomainIds);
    if (!counts.allComplete()) {
      long missing = counts.total() - counts.complete();
      audit.failure(memberId, "scan.complete", "scan", scan.id, scan.organizationId);
      throw ApiException.unprocessable("Beoordeling niet compleet",
          "Nog " + missing + " van de " + counts.total()
              + " vragen in de geselecteerde kennisgebieden missen een antwoord of toelichting.");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    scan.status = ScanEntity.COMPLETED;
    scan.progress = 100;
    scan.completedAt = now;
    scan.updatedAt = now;
    scan.generalComments = comments;
    scan.completionIdempotencyKeyHash = keyHash;
    scan.completionRequestFingerprint = keyHash == null ? null : fingerprint;
    Panache.getEntityManager().flush();
    audit.success(memberId, "scan.complete", "scan", scan.id, scan.organizationId);
    return toResponse(scan);
  }

  // ------------------------------------------------------------------ revisies

  @Transactional
  public ScanDtos.Scan createRevision(UUID memberId, UUID scanId, String idempotencyKey,
      ScanDtos.RevisionInput input) {
    ScanEntity accessible = access.load(memberId, scanId);
    lockOrganization(memberId, accessible.organizationId);
    ScanEntity source = access.lock(memberId, scanId);
    String keyHash = Hashing.requireIdempotencyKey(idempotencyKey);
    String fingerprint = Hashing.fingerprint("revision", source.id, input.mode());
    var replay = ScanEntity.byOrganizationAndIdempotencyKey(source.organizationId, keyHash);
    if (replay.isPresent()) {
      if (!Hashing.constantTimeEquals(replay.get().requestFingerprint, fingerprint)) {
        throw ApiException.idempotencyConflict();
      }
      return toResponse(replay.get());
    }
    if (!source.isCompleted()) {
      throw ApiException.conflict("Scan is niet afgerond", "Alleen van een afgeronde scan kan een revisie worden gemaakt.");
    }
    checkQuota(source.organizationId);
    boolean copyAnswers = "copy".equals(input.mode());
    var em = Panache.getEntityManager();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    ScanEntity revision = new ScanEntity();
    revision.id = UUID.randomUUID();
    revision.organizationId = source.organizationId;
    revision.name = source.name;
    revision.assessmentPeriod = source.assessmentPeriod;
    revision.status = ScanEntity.CONCEPT;
    revision.progress = 0;
    revision.revision = source.revision + 1;
    revision.revisionOf = source.id;
    revision.generalComments = copyAnswers ? source.generalComments : null;
    revision.idempotencyKeyHash = keyHash;
    revision.requestFingerprint = fingerprint;
    revision.createdAt = now;
    revision.updatedAt = now;
    source.goals.forEach(goal -> revision.goals.add(new ScanEntity.Goal(goal.name, goal.priority, goal.customText)));
    revision.persist();
    em.flush();

    // De revisie gebruikt dezelfde (immutable) inhoud als het origineel, met nieuwe snapshot-id's.
    Map<UUID, UUID> domainMap = new HashMap<>();
    Map<UUID, UUID> questionMap = new HashMap<>();
    Map<UUID, UUID> optionMap = new HashMap<>();
    for (SnapshotDomainEntity original : SnapshotDomainEntity.<SnapshotDomainEntity>list("scanId", source.id)) {
      SnapshotDomainEntity copy = new SnapshotDomainEntity();
      copy.id = UUID.randomUUID();
      copy.scanId = revision.id;
      copy.sourceId = original.sourceId;
      copy.name = original.name;
      copy.description = original.description;
      copy.displayOrder = original.displayOrder;
      em.persist(copy);
      domainMap.put(original.id, copy.id);
    }
    em.flush();
    for (SnapshotQuestionEntity original : SnapshotQuestionEntity.<SnapshotQuestionEntity>list("scanId", source.id)) {
      SnapshotQuestionEntity copy = new SnapshotQuestionEntity();
      copy.id = UUID.randomUUID();
      copy.scanId = revision.id;
      copy.snapshotDomainId = domainMap.get(original.snapshotDomainId);
      copy.sourceId = original.sourceId;
      copy.title = original.title;
      copy.text = original.text;
      copy.explanation = original.explanation;
      copy.displayOrder = original.displayOrder;
      em.persist(copy);
      questionMap.put(original.id, copy.id);
    }
    em.flush();
    for (SnapshotOptionEntity original : SnapshotOptionEntity.<SnapshotOptionEntity>list("scanId", source.id)) {
      SnapshotOptionEntity copy = new SnapshotOptionEntity();
      copy.id = UUID.randomUUID();
      copy.scanId = revision.id;
      copy.snapshotQuestionId = questionMap.get(original.snapshotQuestionId);
      copy.sourceId = original.sourceId;
      copy.score = original.score;
      copy.label = original.label;
      copy.description = original.description;
      copy.displayOrder = original.displayOrder;
      em.persist(copy);
      optionMap.put(original.id, copy.id);
    }
    em.flush();
    source.selectedDomainIds.forEach(id -> revision.selectedDomainIds.add(domainMap.get(id)));
    if (copyAnswers) {
      List<AnswerEntity> answers = AnswerEntity.find(
          "select distinct a from AnswerEntity a left join fetch a.evidenceReferences where a.scanId = ?1", source.id)
          .list();
      for (AnswerEntity original : answers) {
        AnswerEntity copy = new AnswerEntity();
        copy.id = UUID.randomUUID();
        copy.scanId = revision.id;
        copy.questionId = questionMap.get(original.questionId);
        copy.optionId = optionMap.get(original.optionId);
        copy.explanation = original.explanation;
        copy.evidenceReferences.addAll(original.evidenceReferences);
        copy.updatedAt = now;
        em.persist(copy);
      }
    }
    em.flush();
    state.apply(revision);
    em.flush();
    audit.success(memberId, "scan.revision.create", "scan", revision.id, revision.organizationId);
    return toResponse(revision);
  }

  private void lockOrganization(UUID memberId, UUID organizationId) {
    access.requireOrganization(memberId, organizationId);
    var organization = OrganizationEntity.findById(organizationId);
    Panache.getEntityManager().refresh(organization, LockModeType.PESSIMISTIC_WRITE);
  }

  private void checkQuota(UUID organizationId) {
    if (ScanEntity.count("organizationId", organizationId) >= maxPerOrganization) {
      throw new ApiException(429, "Scanlimiet bereikt",
          "Het maximale aantal scans voor deze organisatie is bereikt. Revisies tellen ook mee.");
    }
    if (ScanEntity.count("organizationId = ?1 and createdAt >= ?2", organizationId,
        OffsetDateTime.now(ZoneOffset.UTC).minusHours(24)) >= maxPerDay) {
      throw new ApiException(429, "Scanlimiet bereikt",
          "Deze organisatie heeft het maximale aantal nieuwe scans per 24 uur bereikt. Probeer het later opnieuw.");
    }
  }

  // ------------------------------------------------------------------ mapping

  public ScanDtos.Scan toResponse(ScanEntity scan) {
    return toResponses(List.of(scan)).getFirst();
  }

  /** Mapping van meerdere scans met één query voor de volgorde van geselecteerde domeinen. */
  public List<ScanDtos.Scan> toResponses(List<ScanEntity> scans) {
    Set<UUID> allSelected = new HashSet<>();
    scans.forEach(scan -> allSelected.addAll(scan.selectedDomainIds));
    Map<UUID, Integer> order = domainOrder(allSelected);
    List<ScanDtos.Scan> result = new ArrayList<>();
    for (ScanEntity scan : scans) {
      List<UUID> selected = scan.selectedDomainIds.stream()
          .sorted(Comparator.comparing((UUID id) -> order.getOrDefault(id, Integer.MAX_VALUE))
              .thenComparing(UUID::toString))
          .toList();
      List<ScanDtos.OrganizationGoal> goals = scan.goals.stream()
          .map(goal -> new ScanDtos.OrganizationGoal(goal.name, goal.priority, goal.customText)).toList();
      result.add(new ScanDtos.Scan(scan.id, scan.organizationId, scan.name, scan.assessmentPeriod, scan.status,
          scan.progress, scan.revision, scan.revisionOf, selected, scan.version, scan.createdAt, scan.updatedAt,
          scan.completedAt, scan.generalComments, goals));
    }
    return result;
  }

  private static Map<UUID, Integer> domainOrder(Collection<UUID> ids) {
    Map<UUID, Integer> order = new HashMap<>();
    if (ids.isEmpty()) return order;
    List<Object[]> rows = Panache.getEntityManager()
        .createQuery("select d.id, d.displayOrder from SnapshotDomainEntity d where d.id in :ids", Object[].class)
        .setParameter("ids", ids)
        .getResultList();
    rows.forEach(row -> order.put((UUID) row[0], (Integer) row[1]));
    return order;
  }
}
