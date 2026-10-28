"""SYNTHETIC data generator. For testing the code path only.

The sentences are templates written by hand. They are not real promises and not real
protocol text. A model trained on them learns the templates, so any metric measured
on this data says nothing about performance on real protocols.
"""
import random

TOPICS = ["הביטחון", "החינוך", "הבריאות", "התחבורה הציבורית", "הדיור", "הרווחה", "החקלאות", "התרבות"]
PROMISE = ["להגדיל את תקציב {t}", "להשקיע יותר ב{t}", "לקדם רפורמה משמעותית ב{t}"]
SUPPORT = ["אני תומך בהגדלת התקציב של {t} ואפעל לכך בוועדה", "חשוב מאוד לקדם את {t} ולהקצות לכך משאבים",
           "אנחנו מבקשים להרחיב את ההשקעה ב{t} כבר השנה"]
CONTRADICT = ["אני מתנגד להגדלת התקציב של {t} ויש לקצץ בו", "אין מקום להשקיע עוד ב{t} ולכן אצביע נגד",
              "אנחנו דוחים את ההצעה לקדם את {t} ומבקשים להקפיא אותה"]
CONTEXT = ["נשמע כעת את נציג משרד האוצר בנושא {t}", "הדיון בנושא {t} יימשך בישיבה הבאה",
           "אנחנו עוברים לסעיף הבא בסדר היום", "תודה רבה לכל המשתתפים בדיון הזה"]
FILLER = ["", "", " בבקשה לרשום זאת בפרוטוקול.", " זה נאמר גם בישיבה הקודמת.", " אני אומר זאת בכנות."]


def make_synthetic(n=600, n_groups=30, seed=0):
    rng = random.Random(seed)
    rows = []
    for i in range(n):
        t, other = rng.sample(TOPICS, 2)
        label = rng.choice(["support", "contradict", "context"])
        pool = {"support": SUPPORT, "contradict": CONTRADICT, "context": CONTEXT}[label]
        topic_u = other if (label == "context" and rng.random() < 0.5) else t
        rows.append({
            "promise": rng.choice(PROMISE).format(t=t),
            "utterance": rng.choice(pool).format(t=topic_u) + rng.choice(FILLER),
            "label": label,
            "group": f"mk{rng.randrange(n_groups)}",
            "synthetic": True,
        })
    return rows


def make_protocol(rows, speakers):
    """Render rows as a protocol text in the layout protocol_parser expects."""
    lines = ['<< יור >> היו"ר אבי כהן: << יור >>', "אני פותח את הישיבה."]
    for r, sp in zip(rows, speakers):
        lines += [f"{sp}:", r["utterance"]]
    return "\n".join(lines) + "\n"
