# RFC: Point Semantic Graph
## Предварительная семантическая структура для универсального понимания объектов

Status: EXPERIMENTAL / RFC
Scope: research branch / prototype
Do NOT treat as architecture freeze.
Do NOT merge into main solely because this RFC exists.

Автор: владелец, 20.08.2026. Сохранено дословно; теоретический прогон по корпусу — в issue #1176 и в конце сессионного отчёта.

---

# 1. Зачем

Point уже умеет:

Object
→ Understanding / Investigation
→ Graph
→ Ranking
→ Intent / Capability
→ Resolver
→ Realizer
→ updated Graph

Последняя версия зрячей спирали (#1176) доказала, что Graph может постепенно наращивать знание объекта:

Graph₀
→ vision₁
→ Graph₁
→ vision₂
→ Graph₂
→ ...

Но текущий механизм всё ещё в значительной степени опирается на фиксированный набор вопросов вроде:

PHONE / EMAIL / URL / ADDRESS / DATE / AMOUNT / ...

Это недостаточно для произвольного материала:

- фотографии;
- рукописные таблицы;
- скриншоты;
- PDF;
- сканы документов;
- договоры;
- чеки;
- переписки;
- UI;
- QR;
- схемы;
- фотографии физических объектов;
- смешанные изображения.

Цель RFC:

> Сделать Graph способным описывать не только найденные значения, но и материал, его структуру, смысл, связи, состояние знания и возможные действия.

Это НЕ попытка создать универсальную академическую онтологию.
Это минимальная практическая семантика, достаточная для Point.

---

# 2. Главная идея

Не классифицировать объект одним типом.

Неправильно:

    object.kind = RECEIPT

или:

    object.kind = SCREENSHOT

Правильно:

    OBJECT
      MATERIAL
      STRUCTURE
      CONTENT
      RELATIONS
      STATE
      EVIDENCE
      ACTIONS

Один объект может одновременно быть:

    PHOTO
    DOCUMENT
    HANDWRITING
    TABLE

или:

    SCREENSHOT
    UI
    MESSAGE

или:

    PDF
    DOCUMENT
    TABLE

---

# 3. Семантика имеет ортогональные измерения

## 3.1 MATERIAL

Отвечает:

> В каком материале / представлении находится информация?

Предварительный словарь:

    PHOTO
    IMAGE
    SCREENSHOT
    PDF
    DOCUMENT
    AUDIO
    VIDEO

    HANDWRITING
    PRINTED_TEXT

    UI
    CHAT
    LABEL
    RECEIPT
    FORM
    DIAGRAM

    PHYSICAL_OBJECT

Важно:

MATERIAL не является единственным типом объекта.

Например:

    PHOTO + DOCUMENT + HANDWRITING + TABLE

---

# 4. STRUCTURE

Отвечает:

> Как материал организован?

Предварительный словарь:

    PAGE
    BLOCK
    SECTION
    PARAGRAPH

    TABLE
    ROW
    COLUMN
    CELL

    FIELD
    KEY_VALUE
    FORM

    LIST
    ITEM

    HEADER
    FOOTER

    TIMELINE

    MESSAGE
    MESSAGE_THREAD

    UI_CARD
    UI_CONTROL
    UI_LIST
    UI_ROW
    UI_GRID
    UI_BUTTON

    DIAGRAM_NODE
    DIAGRAM_EDGE

Структура может быть вложенной.

Пример:

    DOCUMENT
      ├── PAGE
      │    ├── HEADER
      │    ├── TABLE
      │    │    ├── ROW
      │    │    │    ├── CELL
      │    │    │    └── CELL
      │    │    └── ROW
      │    └── FOOTER
      └── PAGE

Структурный тип не означает semantic meaning.

CELL != AMOUNT
CELL может содержать AMOUNT.

---

# 5. CONTENT

Отвечает:

> Что означает найденное содержание?

Начальный словарь:

## Entities

    PERSON
    ORGANIZATION
    PLACE
    PRODUCT
    VEHICLE
    DOCUMENT
    ACCOUNT
    EVENT

## Contacts / identifiers

    PHONE
    EMAIL
    URL

    IDENTIFIER
    DOCUMENT_NUMBER
    TRACKING_NUMBER
    ACCOUNT_NUMBER

## Values

    DATE
    TIME
    DATETIME

    AMOUNT
    QUANTITY
    UNIT
    CURRENCY

## Common semantic objects

    ADDRESS
    STATUS
    PAYMENT
    ORDER
    SHIPMENT
    OBLIGATION
    MESSAGE

Это только стартовый vocabulary.

Нельзя предполагать, что этот список окончательный.

---

# 6. RELATIONS

Это критически важный слой.

Простое наличие фактов недостаточно.

Нужно знать:

> что с чем связано?

Предварительный словарь:

    contains
    part_of

    belongs_to
    owned_by
    created_by

    sent_by
    sent_to

    works_for
    member_of

    located_at
    delivered_to

    has_amount
    has_quantity
    has_status
    has_date
    has_time

    occurs_at
    expires_at
    created_at
    updated_at

    refers_to
    encodes

    has_participant
    has_obligation

    follows
    precedes

    derived_from
    represents

Relations могут соединять:

- semantic nodes;
- structural nodes;
- objects;
- representations.

---

# 7. STATE

STATE описывает не смысл объекта, а состояние знания о нём.

Уже существующие идеи Point должны быть сохранены и постепенно обобщены.

Минимальный набор:

    UNKNOWN
    FOUND
    PARTIAL
    CONFIRMED

    AMBIGUOUS
    CONTRADICTORY
    UNREADABLE

    INSUFFICIENTLY_INVESTIGATED

STATE не должен заменять provenance/evidence.

Например:

    ADDRESS
      value = "Kyiv..."
      state = FOUND
      provenance = VISION
      evidence = ...

или:

    AMOUNT
      value = "1280"
      state = CONTRADICTORY
      alternatives = ["1250", "1280"]

---

# 8. EVIDENCE

Evidence отвечает:

> Почему Point считает это знанием?

Использовать существующую систему Point, а не создавать новую confidence architecture.

Evidence может происходить из:

    OCR atom
    vision model
    text investigation
    rule
    external execution
    user confirmation
    agreement of independent actors

Дополнительная информация:

    actor
    source
    representation
    location
    provenance

ВАЖНО:

Разные actor names не означают автоматически независимое evidence.

Если:

    MODEL_A → fact
    MODEL_B → reads MODEL_A result

это НЕ два независимых наблюдения.

Независимость должна отражать реальный независимый источник наблюдения.

---

# 9. ACTIONS

Не хранить здесь список конкретных Capability.

ACTIONS должны описывать:

> Что с этим знанием имеет смысл делать человеку?

Предварительный словарь:

    OPEN
    FIND
    LOCATE

    TRACK
    CONTACT

    CALCULATE
    COMPARE
    VERIFY

    SUMMARIZE
    EXTRACT

    EDIT
    TRANSFORM
    EXPORT

    SEND
    SHARE

    CONTINUE

    UNDERSTAND
    UNDERSTAND_MORE

ACTIONS — семантическое намерение.

Capability — конкретный механизм выполнения.

Например:

    TRACK
      ↓
    Capability
      ↓
    Resolver
      ↓
    phone / PC / cloud
      ↓
    Realizer

ACTIONS не должны становиться вторым CapabilityRegistry.

---

# 10. Полная модель

Предварительно:

    OBJECT
    │
    ├── MATERIAL
    │
    ├── STRUCTURE
    │
    ├── CONTENT
    │
    ├── RELATIONS
    │
    ├── STATE
    │
    ├── EVIDENCE
    │
    └── ACTIONS

Но это логическая модель, а НЕ требование хранить всё физически в одном объекте.

Можно и желательно использовать существующий Graph:

    Object
      ├── nodes
      ├── findings
      ├── relations
      ├── representations
      ├── provenance
      └── investigation state

RFC требует прежде всего семантический контракт.

---

# 11. Open Questions

Главное расширение относительно текущего #1176.

Сейчас spiralBrief в основном ищет отсутствие известных типов.

Это должно эволюционировать к:

    OPEN QUESTION

Вопрос может быть:

1. semantic
2. structural
3. relational
4. investigative
5. actionable

Примеры:

    "Что это за документ?"

    "Есть ли в таблице итоговая строка?"

    "Кому принадлежит номер?"

    "Что означает эта сумма?"

    "Есть ли обязательство?"

    "Есть ли дата исполнения?"

    "Что представляет собой этот вложенный объект?"

    "Есть ли на второй странице таблица?"

Вопрос НЕ обязан быть естественно-языковой строкой.

Внутренне это может быть:

    missing relation
    missing semantic node
    incomplete structure
    unresolved contradiction
    insufficient evidence
    unknown document role

---

# 12. Understanding должен работать не от списка KEY

Текущий прототип:

    PHONE
    EMAIL
    URL
    ADDRESS
    ...

остаётся допустимым первым approximation для #1176.

Но конечная система должна работать примерно так:

    Graph
      ↓
    known structure
    + known content
    + relations
    + unresolved state
      ↓
    open questions
      ↓
    next investigation
      ↓
    Graph'

То есть вопрос:

> "Что ещё можно найти?"

должен постепенно превращаться в:

> "Что ещё нужно понять, чтобы объект стал достаточно понятным для полезного действия?"

---

# 13. Примеры

## 13.1 Рукописная таблица

Вход:

    фото листа:

    Продукт | Кол-во | Цена
    яблоки  | 12     | 450
    груши   | 8      | 320

Graph:

    MATERIAL:
      PHOTO
      HANDWRITING

    STRUCTURE:
      TABLE
      ROW
      CELL
      HEADER

    CONTENT:
      PRODUCT
      QUANTITY
      AMOUNT
      CURRENCY

    RELATIONS:
      PRODUCT → HAS_QUANTITY → QUANTITY
      PRODUCT → HAS_PRICE → AMOUNT

Open questions:

    all rows investigated?
    total exists?
    currency known?
    handwritten cell uncertain?

Possible actions:

    CALCULATE TOTAL
    EXPORT
    UNDERSTAND_MORE

---

# 14.2 Screenshot банковского приложения

Input:

    Screenshot

    Получатель: ООО Ромашка
    1 250 грн
    20.08.2026
    [Оплатить]

Graph:

    MATERIAL:
      SCREENSHOT
      UI

    STRUCTURE:
      UI_CARD
      KEY_VALUE
      UI_BUTTON

    CONTENT:
      ORGANIZATION
      AMOUNT
      CURRENCY
      DATE
      PAYMENT

    RELATIONS:
      AMOUNT → BELONGS_TO → PAYMENT
      PAYMENT → RECIPIENT → ORGANIZATION
      BUTTON → ACTS_ON → PAYMENT

Possible actions:

    VERIFY
    OPEN_RECIPIENT
    CONTINUE_PAYMENT

Не показывать человеку "OCR", "Extract", "Read UI".

---

# 14.3 Скан договора

Graph:

    MATERIAL:
      PDF
      DOCUMENT

    STRUCTURE:
      PAGE
      SECTION
      PARAGRAPH
      TABLE
      SIGNATURE_BLOCK

    CONTENT:
      PERSON
      ORGANIZATION
      DATE
      AMOUNT
      ADDRESS
      OBLIGATION

    RELATIONS:
      PARTY → HAS_OBLIGATION → OBLIGATION
      OBLIGATION → DUE_AT → DATE
      OBLIGATION → HAS_AMOUNT → AMOUNT

Possible open questions:

    obligations complete?
    deadlines found?
    all parties identified?
    appendix exists?
    unreadable page?

Possible actions:

    SUMMARIZE
    EXTRACT_OBLIGATIONS
    VERIFY_DEADLINES
    COMPARE

---

# 15.4 Скрин переписки

Input:

    "Давай встретимся завтра в 18:00 возле Оперного.
     Я буду с Иваном."

Graph:

    MATERIAL:
      SCREENSHOT
      CHAT

    STRUCTURE:
      MESSAGE
      MESSAGE_THREAD

    CONTENT:
      PERSON
      DATE
      TIME
      PLACE
      EVENT

    RELATIONS:
      EVENT → PARTICIPANT → PERSON
      EVENT → OCCURS_AT → DATETIME
      EVENT → LOCATED_AT → PLACE

Possible action:

    CREATE_EVENT

Не показывать:

    EXTRACT DATE
    EXTRACT PERSON
    EXTRACT PLACE

---

# 15.5 Фото QR

Graph:

    MATERIAL:
      PHOTO

    CONTENT:
      QR
      URL

    RELATIONS:
      QR → ENCODES → URL

State:

    QR = CONFIRMED
    URL = FOUND

Possible action:

    OPEN

"Считать QR" больше не является meaningful next step после успешного чтения.

---

# 15.6 Фото физического объекта

Input:

    фото роутера

Graph:

    MATERIAL:
      PHOTO
      PHYSICAL_OBJECT

    CONTENT:
      PRODUCT
      BRAND
      MODEL

Open questions:

    exact model?
    specifications?
    manual?
    compatibility?

Possible actions:

    FIND_MANUAL
    FIND_SPECIFICATIONS
    COMPARE

---

# 15.7 Смешанный объект

Фото страницы:

    договор
    таблица
    QR
    подпись
    рукописная дата

Graph:

    DOCUMENT
      └── PAGE
          ├── TABLE
          ├── QR
          ├── SIGNATURE
          └── HANDWRITING

Каждый компонент может иметь собственное semantic graph substructure.

Point НЕ обязан выбирать один "тип картинки".

---

# 16. Mixed / multimodal objects

Это обязательное свойство RFC.

Point должен поддерживать:

    PHOTO
      + DOCUMENT
      + TABLE
      + HANDWRITING

    SCREENSHOT
      + UI
      + MESSAGE
      + PERSON
      + URL

    PDF
      + DOCUMENT
      + TABLE
      + IMAGE
      + HANDWRITING

Не использовать:

    one object → one type

---

# 17. Semantic types ≠ document types

Не делать:

    RECEIPT
    CONTRACT
    INVOICE
    PASSPORT
    ...

единственным центральным enum.

Document type может быть отдельной hypothesis:

    DOCUMENT_CLASS = RECEIPT

Но Receipt всё равно состоит из:

    PARTY
    DATE
    AMOUNT
    ITEMS
    IDENTIFIER
    ...

А скан договора может содержать таблицу, события, обязательства, людей и суммы.

---

# 18. Semantic types ≠ UI types

Не смешивать:

    UI_BUTTON
    PHONE
    ADDRESS

UI_BUTTON — структура интерфейса.

PHONE — semantic content.

Их связь может быть:

    UI_BUTTON → ACTS_ON → PHONE

или:

    UI_BUTTON → ACTS_ON → PAYMENT

---

# 19. Semantic types ≠ capabilities

Не делать:

    TRACKING_NUMBER = TRACK_CAPABILITY

Правильно:

    TRACKING_NUMBER
        ↓
    possible action: TRACK
        ↓
    Capability
        ↓
    Realizer

Один semantic fact может порождать несколько actions.

Один action может использовать разные semantic facts.

---

# 20. Understanding cycle

Предлагаемый внутренний цикл:

    INPUT OBJECT
        ↓
    INITIAL INVESTIGATION
        ↓
    GRAPH
        ↓
    OPEN QUESTIONS
        ↓
    TARGETED INVESTIGATION
        ↓
    GRAPH DELTA
        ↓
    MERGE / EVIDENCE
        ↓
    UPDATED GRAPH
        ↓
    OPEN QUESTIONS'
        ↓
    ACTION RANKING
        ↓
    HUMAN ACTION
        ↓
    UPDATED GRAPH

Это НЕ новая state machine.

Это логическая последовательность существующего Point flow.

---

# 21. Важный принцип: действия должны закрывать вопросы

Пример:

    OPEN QUESTION:
      "какой адрес доставки?"

Если Graph уже содержит:

    ADDRESS

то вопрос закрыт.

И тогда:

    EXTRACT_ADDRESS

не должно попадать в top actions.

Если есть:

    ADDRESS

но неизвестно:

    shipment → delivered_to → address

то возможно имеет смысл другое действие:

    TRACK_SHIPMENT
    VERIFY_DELIVERY

Таким образом действие определяется не только наличием value, но и его ролью.

---

# 22. Ranking

Существующий LearningBubblePolicy должен постепенно эволюционировать от:

    "какие capabilities подходят?"

к:

    "какие meaningful actions наиболее полезны?"

При этом критерии:

    knowledge already present
    open questions
    unresolved relations
    uncertainty
    user context
    capability availability
    execution cost
    destructive side effects

Но не вводить отдельный planner framework.

Использовать существующий ranking.

---

# 23. LLM role

LLM не получает полномочия определять Graph напрямую.

Он может:

    propose candidate
    propose relation
    propose open question
    propose next action

Point затем валидирует:

    semantic type
    identity
    provenance
    evidence
    existing graph
    capability applicability

Принцип:

    LLM proposes
    Point validates
    Graph decides

---

# 24. Неизвестные семантические типы

Система НЕ обязана заранее знать всё.

Допустимо:

    UNKNOWN_ENTITY
    UNKNOWN_RELATION
    UNKNOWN_STRUCTURE

Но неизвестность должна быть явной.

Нельзя автоматически превращать:

    UNKNOWN

в:

    PERSON
    ORGANIZATION
    URL
    PHONE

только потому что текст "похож".

---

# 25. Minimal viable vocabulary

Для первого прототипа НЕ нужно реализовывать сотню типов.

Начальный набор:

## Material

    PHOTO
    SCREENSHOT
    PDF
    DOCUMENT
    HANDWRITING
    UI
    CHAT
    AUDIO

## Structure

    PAGE
    BLOCK
    TABLE
    ROW
    COLUMN
    CELL
    FIELD
    KEY_VALUE
    LIST
    MESSAGE
    UI_CONTROL

## Content

    PERSON
    ORGANIZATION
    PLACE
    ADDRESS

    PHONE
    EMAIL
    URL

    DATE
    TIME
    DATETIME

    AMOUNT
    QUANTITY
    UNIT
    CURRENCY

    IDENTIFIER
    DOCUMENT_NUMBER
    TRACKING_NUMBER

    PRODUCT
    EVENT
    PAYMENT
    STATUS
    OBLIGATION

## Relations

    contains
    part_of
    belongs_to
    has_value
    has_date
    has_time
    has_amount
    located_at
    delivered_to
    refers_to
    encodes
    participant
    occurs_at
    has_obligation
    derived_from

Этого должно хватить для первых экспериментов.

---

# 26. Экспериментальная задача для Claude

НЕ реализовывать всю RFC.

Сначала:

1. Изучить текущий Graph / Object / Investigation / Entity / Capability contracts.
2. Показать, какая часть RFC уже существует в коде.
3. Найти минимальное место, куда можно добавить semantic layer без нового storage.
4. Прототипировать только vocabulary + representation.
5. Не менять существующий public UI.
6. Не заменять текущие entity types.
7. Не ломать #1176.
8. Использовать существующие:
   - Provenance
   - InvestigationState
   - sameFact
   - Relations
   - Graph
   - CurrentKnowledge
   - LearningBubblePolicy
9. Сделать экспериментальный mapping для минимум семи кейсов:
   - рукописная таблица
   - банковский screenshot
   - скан договора
   - screenshot переписки
   - QR
   - физический объект
   - смешанный документ
10. Сравнить:
    - сколько существующих механизмов переиспользовано;
    - сколько новых типов реально понадобилось;
    - какие типы оказались искусственными;
    - какие отношения нельзя выразить текущим Graph.
11. НЕ переходить автоматически к массовому внедрению.

---

# 27. Критерий успеха RFC

RFC считается удачным экспериментом, если одна и та же модель может выразить:

    handwritten table
    screenshot
    scanned document
    chat
    QR
    physical object
    mixed document

без:

    special-case code per object type
    new state machine
    new storage
    capability-specific semantic hacks

И при этом существующий Point:

    Object
      → Understanding
      → Graph
      → Ranking
      → Intent
      → Capability
      → Realizer
      → Object

остаётся тем же.

---

# 28. Что НЕ является целью

Не создавать:

- универсальную ontology;
- knowledge graph platform;
- autonomous agent;
- chatbot;
- LLM planner;
- отдельную semantic database;
- сотни типов документов;
- полный computer vision scene graph;
- новую систему confidence.

Цель:

> Дать существующему Point минимальный общий язык для описания материала, структуры, содержания, отношений и открытых вопросов — чтобы Graph мог становиться всё более осмысленным и сам определять следующий полезный шаг.

---

# 29. Ключевая гипотеза

Если RFC верна, то в будущем:

    "Фото квитанции"

не будет для Point отдельной feature.

Он увидит:

    MATERIAL = PHOTO
    STRUCTURE = DOCUMENT
    CONTENT = PARTY + DATE + AMOUNT + ...
    RELATIONS = ...
    STATE = ...
    OPEN QUESTIONS = ...
    ACTIONS = ...

То же ядро будет работать для:

    рукописной таблицы
    скриншота
    договора
    чата
    QR
    предмета
    смешанного документа

И тогда Point действительно сможет перейти от:

> "Какой инструмент применить к этому файлу?"

к:

> "Что это, что уже понятно, чего ещё не хватает и какой следующий шаг будет человеку полезнее всего?"

---

# 30. Final RFC stance

Это экспериментальная гипотеза.

НЕ объявлять эту структуру новой Architecture Freeze.

НЕ делать массовый refactor до проверки.

Сначала доказать на реальных корпусах Point:

    table
    screenshot
    scan
    handwriting
    chat
    QR
    physical object
    mixed document

Если структура выдержит эти кейсы без специальных исключений, следующий шаг — определить минимальный canonical vocabulary и только затем решить, какие части действительно должны стать кодом Point.
