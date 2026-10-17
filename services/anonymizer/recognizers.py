"""Presidio recognizers for identifiers that matter in Rwanda.

The English spaCy model knows few Kinyarwanda names or Rwandan places, so names and districts are also matched
from deny lists. The lists here are synthetic samples for the demo; production lists come from the national
registries and are maintained like any other reference data.
"""

from presidio_analyzer import AnalyzerEngine, Pattern, PatternRecognizer, RecognizerRegistry
from presidio_analyzer.nlp_engine import NlpEngineProvider

NATIONAL_ID = PatternRecognizer(
    supported_entity="RW_NATIONAL_ID",
    name="Rwanda national ID",
    patterns=[
        # 16 digits, optionally grouped with spaces (1 YYYY 8 0012345 0 12 style) or written as one run
        Pattern("national id", r"(?<!\d)\d(?:[ -]?\d){15}(?!\d)", 0.75),
    ],
    context=["id", "national", "indangamuntu", "nid", "identity"],
)

PHONE = PatternRecognizer(
    supported_entity="RW_PHONE",
    name="Rwanda mobile number",
    patterns=[
        Pattern("+250 / 250 / 0 prefix", r"(?<!\d)(?:\+?250[ -]?|0)7[2389](?:[ -]?\d){7}(?!\d)", 0.85),
    ],
    context=["phone", "tel", "call", "number", "msisdn", "mobile"],
)

UPI = PatternRecognizer(
    supported_entity="RW_UPI",
    name="Unique patient identifier (YYMMDD-FOSAID-RAND4)",
    patterns=[Pattern("upi", r"(?<!\d)\d{6}-\d{4}-\d{4}(?!\d)", 0.9)],
)

NAMES = PatternRecognizer(
    supported_entity="PERSON",
    name="Kinyarwanda and French names (deny list)",
    deny_list=[
        "Uwase", "Keza", "Mukamana", "Habimana", "Uwimana", "Niyonzima", "Mugisha", "Ishimwe", "Nshuti", "Kamanzi",
        "Munyaneza", "Nkurunziza", "Ingabire", "Uwera", "Hakizimana", "Nsengimana", "Twagirayezu", "Bizimana",
        "Ndayisaba", "Mutoni", "Iradukunda", "Umutoni", "Gatete", "Kalisa", "Mukeshimana", "Nyirahabimana",
        "Marie", "Jeanne", "Claudine", "Eric", "Jean", "Pierre", "Aline", "Diane", "Emmanuel", "Olivier",
    ],
)

DISTRICTS = PatternRecognizer(
    supported_entity="LOCATION",
    name="Rwanda districts and sectors (deny list)",
    deny_list=[
        "Gasabo", "Kicukiro", "Nyarugenge", "Musanze", "Huye", "Rubavu", "Nyagatare", "Rwamagana", "Bugesera",
        "Kayonza", "Kirehe", "Ngoma", "Gatsibo", "Rusizi", "Nyamasheke", "Karongi", "Rutsiro", "Ngororero",
        "Nyabihu", "Burera", "Gakenke", "Gicumbi", "Rulindo", "Muhanga", "Kamonyi", "Ruhango", "Nyanza",
        "Gisagara", "Nyaruguru", "Nyamagabe", "Kimironko", "Remera", "Kacyiru", "Nyamirambo",
    ],
)

ENTITIES = ["RW_NATIONAL_ID", "RW_PHONE", "RW_UPI", "PERSON", "LOCATION", "EMAIL_ADDRESS"]


def build_analyzer() -> AnalyzerEngine:
    nlp = NlpEngineProvider(nlp_configuration={
        "nlp_engine_name": "spacy",
        "models": [{"lang_code": "en", "model_name": "en_core_web_sm"}],
    }).create_engine()
    registry = RecognizerRegistry(supported_languages=["en"])
    registry.load_predefined_recognizers(nlp_engine=nlp, languages=["en"])
    for r in (NATIONAL_ID, PHONE, UPI, NAMES, DISTRICTS):
        registry.add_recognizer(r)
    return AnalyzerEngine(registry=registry, nlp_engine=nlp, supported_languages=["en"])
