# Signal Lab EGX: decisions on the Arabic tables

### E5: Arabic tables (session D)

Item 38 of `docs/egx-build-list.md`, written 2026-11-14. Files: `app/src/main/assets/egx/names_ar.tsv` (220 codes, every
row of EGX Analyzer's `yahoo_symbols.json`) and `app/src/main/assets/egx/strings_ar.tsv` (435 rows, of them 38 glossary
rows). The owner reads both before the install (item 41).

- **Key.** `strings_ar.tsv` is keyed by the English source text, as asked; the lead maps it to the final keys at item 37.
  Kotlin templates are written with `%1$s` / `%1$d` placeholders in the English column, in the order the Arabic uses.
- **Register.** Labels and buttons follow the accepted mockup (اشترِ، بِع، وضعتُ الأمر، الحساب الورقي، أوامر مجانية).
  Sentences use light Egyptian spoken Arabic as Egyptian trading apps do (مفيش، دلوقتي، النهارده، بكرة، استنى). The
  mockup has two Modern Standard words (غدًا، هذا الشهر); they are kept as written there. If the owner wants one register
  throughout, this is the first thing to change.
- **Counting.** Arabic plurals depend on the number, so the Today sentence is built from parts: one (سهمًا), two (سهمين),
  3 to 10 (%1$d أسهم), 11 and more (%1$d سهمًا). The mockup's "غدًا: اشترِ سهمين وبِع سهمًا." is kept as a full row.
  After a number on cards the app writes "390 سهم", as the mockup and Egyptian broker screens do.
- **Market terms.** كوبون for dividend; تاريخ نهاية الحق for the last day that carries it; صرف الكوبون for payment;
  وقف الخسارة; الهدف الأول / الهدف الثاني; نطاق الدخول; الحد السعري for the price limit; وحدة تغيّر السعر for the tick;
  قيمة التداول for turnover; فرق السعر for spread; ضريبة الدمغة; الكاش for the cash yardstick (mockup) and صندوق النقد
  for the fund; المؤشر for the EGX30 (mockup), with "EGX30" written in Latin where it is named in full.
- **Strategy names.** Dividend run = قبل الكوبون (the mockup's card line; plainer than a literal "run"). Calm stocks =
  الأسهم الهادئة (mockup). Dividend payers = الأسهم الموزّعة. Slow breakout = الاختراق البطيء; Fast breakout =
  الاختراق السريع. Low-risk formula = معادلة المخاطرة القليلة.
- **Tiers.** Proven مُثبتة, Promising واعدة, Watch تحت المراقبة, Testing تحت التجربة, Failing متعثّرة.
- **Thndr.** ثاندر inside Arabic sentences; Latin "Thndr" is fine on a button with its logo. The app name "Signal Lab"
  stays in Latin letters.
- **Pounds.** ج after an amount on cards and tiles (mockup "12,500 ج"); جنيه in sentences.
- **Dates.** Egyptian month names (يناير … ديسمبر), day before month ("15 نوفمبر").
- **Crypto rows.** The crypto app's shared screens are included with note `crypto`, so it can get Arabic later; texts
  that are only test tags, file names or code were left out.
- **Company names.** Written as the exchange and the financial press write them, mostly the registered Arabic name, with
  the brand after a dash where Egyptians use it (موبكو، سوديك، إيبيكو، كابو). Codes that share one company get the same
  name: EFHI/EFIH, FAIT/FAITA (with بالجنيه / بالدولار), KRDI/NAKH, QNBA/QNBE, VLMR/VLMRA.
- **Names I am not sure of** (best guess written; the owner or EGX's site should confirm): ACAMD, ACAP, ACTF, AIHC,
  AMII, ASPI, CFGH, COPR, CPME, CRST, EGAS, GMCI, GPIM, GRCA, GTEX, GTWL, ICID, IDRE, LUTS, MBEG, NARE, NDRL, PHGC,
  RREI, UEGC, VLMR/VLMRA, KRDI/NAKH (which of the two codes is current), EFHI (likely an old code of EFIH).
