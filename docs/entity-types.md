# Domains & Entity Types

Pre-configured domains and entity types for the personal assistant.
Rows marked ✓ are already seeded in the database. All others are planned additions.

---

## personal_details
Contact info, addresses, government IDs, and personal milestones.

| Entity Type | Description |
|---|---|
| `identity_document` | Government-issued IDs: Driver's License, Passport, OCI, SSN, PAN, Aadhaar, Visa stamps, I-797 immigration notices. One record per document per person. |
| `birthday_anniversary` | Birthdays and anniversaries for family members and close friends — name, date, relationship, notes. |

---

## finance
All money in, money out, tax obligations, and informal lending.

| Entity Type | Description |
|---|---|
| `payslip` ✓ | Monthly/bi-weekly pay stubs from employer — gross, tax, net, pay period. |
| `expense` | Recurring or one-off expenses: subscriptions, fees, utility bills, miscellaneous. Tracks amount, category, frequency, and vendor. |
| `tax_filing` | Annual income tax returns for India and the US, plus FBAR filings for foreign bank accounts. Tracks filing year, jurisdiction, status, and key figures. |
| `reimbursement` | Work or personal reimbursement claims — amount, reason, submitted date, reimbursed date. |
| `lend_borrow` | Informal money lent to or borrowed from someone (lena-dena). Tracks person, amount, date, purpose, and settlement status. |

---

## employment
Jobs, career progression, and employer records.

| Entity Type | Description |
|---|---|
| `job` ✓ | Employment record — employer, role, salary, start date, end date. |
| `performance_review` | Annual or periodic appraisal letters and performance assessments — cycle, rating, key notes, and attached document. |

---

## health
Medical records for self, spouse, and kids.

| Entity Type | Description |
|---|---|
| `insurance_card` ✓ | Health insurance card details — provider, plan, deductible, premium, coverage dates, per person. |
| `appointment` | Medical visits and preventive checkups (annual physicals, AVs) — doctor, date, purpose, outcome, and follow-ups. |
| `medication` | Current prescriptions and over-the-counter medications — name, dosage, frequency, prescribing doctor, start/end dates. |
| `vaccination` | Vaccination history and upcoming shots — vaccine, date, provider, next due date, per person. |
| `diet_plan` | Diet charts, nutritional guidance, and calorie targets — active plan with start date, notes, and attached document. |
| `exercise_plan` | Exercise routines and fitness charts — type of workout, schedule, goals, and attached document. |

---

## todo
Tasks and reminders, both one-off and recurring.

| Entity Type | Description |
|---|---|
| `todo_item` ✓ | Adhoc or recurring task — title, status, due date, priority, recurrence rule. |

---

## household
Shared family logistics, home management, and events.

| Entity Type | Description |
|---|---|
| `event` | Family functions, celebrations, and gatherings (festivals, didis, milestones) — name, date, attendees, venue, notes. |

---

## assets
Physical assets owned — real estate and vehicles.

| Entity Type | Description |
|---|---|
| `property` | Residential or investment property — address, purchase date, purchase price, mortgage details, insurance history, and attached deed/documents. |
| `vehicle` | Cars and other vehicles — make, model, year, VIN, purchase date, service history entries, and insurance history. |

---

## travel
Trip records and vacation history.

| Entity Type | Description |
|---|---|
| `vacation` | Trip history — destination, travel dates, travelers, accommodation, highlights, and attached itinerary or photos. |

---

## legal
Legal instruments and formal ownership documents.

| Entity Type | Description |
|---|---|
| `trust_document` | Trust deeds and related legal documents — trust name, type, date executed, trustees, beneficiaries, and attached document. |

---

## kids
Records specific to children — school, activities, and social events.

| Entity Type | Description |
|---|---|
| `school_record` | School-related records per child — academic year, school name, grade, teacher contacts, report cards, and key communications. |
| `camp` | Summer or activity camp history — camp name, type, dates, cost, notes per child. |
| `class` | Extracurricular classes (music, sport, art, coding, etc.) — class name, provider, schedule, cost, start/end dates per child. |
| `party` | Birthday and holiday party history — event name, date, venue, guest list, theme, notes per child. |

---

## social
Gifts and social obligations tracked across relationships.

| Entity Type | Description |
|---|---|
| `gift` | Gifts received or given — occasion, person, item description, value, date. Covers both directions so reciprocity can be tracked. |

---

## news_preferences
*(Domain exists; no new entity types planned — managed via preferences on the person record.)*
