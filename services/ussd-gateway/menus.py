"""USSD screens in Kinyarwanda and English. Every screen must fit one USSD page (checked in tests and at runtime).
Kinyarwanda wording to be reviewed by a native speaker before real use."""

MAX_SCREEN = 182  # the common network limit for one USSD page

T = {
    "welcome": {"en": "Murakaza neza kuri TeleClinic\n1. Kinyarwanda\n2. English",
                "rw": "Murakaza neza kuri TeleClinic\n1. Kinyarwanda\n2. English"},
    "pin": {"en": "TeleClinic\nEnter your 4-digit PIN:", "rw": "TeleClinic\nShyiramo PIN yawe y'imibare 4:"},
    "wrong_pin": {"en": "Wrong PIN, {left} tries left.\nEnter your PIN:", "rw": "PIN si yo. Usigaje inshuro {left}.\nShyiramo PIN:"},
    "locked": {"en": "Too many wrong PINs. Try again in 15 minutes.", "rw": "Wibeshye PIN inshuro nyinshi. Ongera ugerageze nyuma y'iminota 15."},
    "not_linked": {"en": "This number is not linked to IremboAccount. Link it once on irembo.gov.rw.",
                   "rw": "Iyi nomero ntihuzwa na IremboAccount. Yihuze rimwe kuri irembo.gov.rw."},
    "who": {"en": "Who is sick?", "rw": "Ni nde urwaye?"},
    "me": {"en": "me", "rw": "njye"},
    "child": {"en": "child", "rw": "umwana"},
    "consent": {"en": "{name}: TeleClinic uses your answers to treat and to take payment.\n1. Agree\n2. Agree, with AI support for the nurse\n3. Cancel",
                "rw": "{name}: TeleClinic ikoresha ibisubizo byawe mu kuvura no kwishyura.\n1. Ndabyemeye\n2. Ndabyemeye, na AI ifasha umuforomo\n3. Hagarika"},
    "symptoms": {"en": "{head}\n1. Fever\n2. Cough\n3. Fast breathing\n4. Diarrhoea\n5. Vomiting\n6. Danger sign\n0. Done",
                 "rw": "{head}\n1. Umuriro\n2. Inkorora\n3. Guhumeka vuba\n4. Gucibwamo\n5. Kuruka\n6. Ikimenyetso gikomeye\n0. Ndarangije"},
    "symptoms_head": {"en": "Symptoms of {name}? One at a time", "rw": "Ibimenyetso bya {name}? Kimwe kimwe"},
    "symptoms_added": {"en": "Added {n}. Another, or 0 when done", "rw": "Byongewemo {n}. Ikindi, cyangwa 0"},
    "danger": {"en": "Danger sign:\n1. Difficulty breathing\n2. Convulsions\n3. Unconscious\n4. Severe bleeding\n5. Chest pain",
               "rw": "Ikimenyetso gikomeye:\n1. Kubura umwuka\n2. Kugagara\n3. Guta ubwenge\n4. Kuva amaraso menshi\n5. Kubabara mu gituza"},
    "need_one": {"en": "Choose at least one symptom.", "rw": "Hitamo nibura ikimenyetso kimwe."},
    "days": {"en": "For how many days?\n1. 1 day\n2. 2 days\n3. 3 days\n4. 4-6 days\n5. 7+ days",
             "rw": "Hashize iminsi ingahe?\n1. Umunsi 1\n2. Iminsi 2\n3. Iminsi 3\n4. Iminsi 4-6\n5. Iminsi 7+"},
    "advice": {"en": "{urgency}: {advice}\n1. Book a clinician\n2. Cancel", "rw": "{urgency}: {advice}\n1. Saba umuganga\n2. Hagarika"},
    "paid_prompt": {"en": "Approve the prompt to pay {amount} RWF{cover}. You join the queue when paid and get an SMS.",
                    "rw": "Emeza ubutumwa bwo kwishyura {amount} RWF{cover}. Ujya ku murongo umaze kwishyura, ukabona SMS."},
    "cover": {"en": " (Mutuelle covers the rest)", "rw": " (Mutuelle yishyura ibisigaye)"},
    "bye": {"en": "Thank you. Dial again any time.", "rw": "Murakoze. Mwongere muhamagare igihe icyo ari cyo cyose."},
    "busy": {"en": "Sorry, the service is busy. Please dial again.", "rw": "Mutwihanganire, serivisi irahuze. Ongera uhamagare."},
    "invalid": {"en": "Invalid choice.", "rw": "Ihitamo ritemewe."},
}

URGENCY = {
    "EMERGENCY": {"en": "EMERGENCY", "rw": "BYIHUTIRWA CYANE"},
    "URGENT": {"en": "URGENT", "rw": "BYIHUTIRWA"},
    "SEMI_URGENT": {"en": "TODAY", "rw": "UYU MUNSI"},
    "ROUTINE": {"en": "ROUTINE", "rw": "BISANZWE"},
}

SYMPTOM_CODES = {"1": "fever", "2": "cough", "3": "fast_breathing", "4": "diarrhoea", "5": "vomiting"}
DANGER_CODES = {"1": "difficulty_breathing", "2": "convulsions", "3": "unconscious", "4": "severe_bleeding", "5": "chest_pain"}
DAYS = {"1": 1, "2": 2, "3": 3, "4": 5, "5": 7}


def t(key: str, lang: str, **kw) -> str:
    return T[key][lang].format(**kw)


def fit(text: str) -> str:
    """Never send more than one page; shorten the first line if a long advice text would overflow."""
    if len(text) <= MAX_SCREEN:
        return text
    head, _, menu = text.partition("\n")
    room = MAX_SCREEN - len(menu) - 2
    return head[: max(room, 20)].rstrip() + "…\n" + menu
