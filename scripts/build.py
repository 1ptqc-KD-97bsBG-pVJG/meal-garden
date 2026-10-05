"""Validate recipes, render the active plan, and calculate grocery demand."""

from fractions import Fraction
from pathlib import Path
import hashlib
import html
import json
import math

import os

CODE = Path(__file__).resolve().parents[1]
# Personal data (recipes, plans, generated cards) may live outside the repository.
ROOT = Path(os.environ.get("MEAL_DATA_DIR", CODE / "sample-household")).resolve()


def load(path):
    return json.loads(path.read_text())


def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def fmt(value):
    return str(Fraction(value).limit_denominator(16))


def esc(value):
    return html.escape(str(value), quote=True)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def active_plan():
    plans = []
    for path in sorted((ROOT / "data/plans").glob("*.json")):
        plan = load(path)
        if plan.get("status") == "active":
            plans.append((path, plan))
    assert len(plans) == 1, f"Expected one active plan, found {len(plans)}"
    return plans[0]



# Appliances that can only do one thing at a time. Counter work, bowls and boards are ignored.
APPLIANCES = {"duxtop": "Duxtop", "ninja": "Ninja", "stove": "Stove", "oven": "Oven", "microwave": "Microwave", "air fryer": "Air fryer"}


def appliance(label):
    head = (label or "").split("·")[0].strip().lower()
    if not head or head.startswith(("counter", "worktop", "prep", "cutting board")):
        return None
    return next((name for key, name in APPLIANCES.items() if head.startswith(key)), head)


def timeline_warnings(recipe):
    """Warn (never fail) when a timed recipe double-books an appliance or overruns its stated total time.

    Steps opt in with start_minute (from the start of the cook) and minutes (active plus passive).
    Stove steps are allowed to overlap because a stove has several burners.
    """
    warnings = []
    steps = recipe.get("steps", [])
    timed = [(i, s) for i, s in enumerate(steps, 1) if isinstance(s.get("start_minute"), (int, float)) and isinstance(s.get("minutes"), (int, float))]
    if not timed:
        return warnings
    untimed = [i for i, s in enumerate(steps, 1) if (i, s) not in timed]
    if untimed:
        warnings.append(f"steps {untimed} have no start_minute/minutes, so the timeline check is partial")
    by_appliance = {}
    for i, s in timed:
        name = appliance(s.get("equipment"))
        if name and name != "Stove":
            by_appliance.setdefault(name, []).append((s["start_minute"], s["start_minute"] + s["minutes"], i))
    for name, spans in by_appliance.items():
        spans.sort()
        for (a0, a1, ai), (b0, b1, bi) in zip(spans, spans[1:]):
            # Consecutive steps in the same vessel (e.g. brown then add spinach) are fine when they don't overlap.
            if b0 < a1:
                warnings.append(f"{name} is double-booked: step {ai} runs minutes {a0}-{a1} and step {bi} starts at {b0}")
    end = max(s["start_minute"] + s["minutes"] for _, s in timed)
    total = recipe.get("total_minutes")
    if isinstance(total, (int, float)) and end > total * 1.1 + 2:
        warnings.append(f"timed steps end at minute {end}, beyond total_minutes {total}")
    return warnings


def main():
    household_path = ROOT / "household.json"
    household = load(household_path) if household_path.exists() else {}
    person = household.get("person", {})
    person_id = person.get("id", "owner")
    person_name = person.get("name", "You")
    recipes = {}
    for path in sorted((ROOT / "recipes").glob("*/recipe.json")):
        recipe = load(path)
        assert recipe["schema_version"] == 1 and recipe["id"] == path.parent.name
        assert recipe["id"] not in recipes and recipe["title"]
        recipes[recipe["id"]] = recipe
        if recipe["readiness"] == "ready":
            assert recipe.get("ingredients") and recipe.get("steps")
            for item in recipe["ingredients"]:
                assert item["amount"] > 0 and item["unit"]
                assert item["purchase"]["capacity"] > 0
            for step in recipe["steps"]:
                assert step["title"] and step["text"]

    plan_path, plan = active_plan()
    template_path = CODE / "templates/phone-recipe-page.html"
    demand = {}
    cards = []

    for meal in plan["meals"]:
        recipe = recipes[meal["recipe_id"]]
        assert recipe["readiness"] == "ready"
        assert meal["recipe_revision"] == recipe["revision"]
        batches = meal.get("batches", 1)
        assert batches == 1, "Review cooking and storage before scaling a prep batch."

        for item in recipe["ingredients"]:
            key = (item["id"], item["unit"])
            if key not in demand:
                demand[key] = dict(item, amount=0, recipes=[])
            row = demand[key]
            assert row["purchase"] == item["purchase"]
            row["amount"] += item["amount"] * batches
            row["recipes"].append(recipe["title"])

        recipe_id = recipe["id"]
        source = ROOT / "recipes" / recipe_id / "recipe.json"
        content_hash = sha(source)
        renderer_hash = hashlib.sha256((sha(template_path) + sha(Path(__file__))).encode()).hexdigest()

        ingredient_rows = []
        for item in recipe["ingredients"]:
            ingredient_rows.append(
                '<label class="check-item" data-task-label="Gather {name}"><input type="checkbox" '
                'aria-label="Gather {name}"><span class="item-body"><span class="item-title">{name}</span>'
                '<span class="item-detail">{amount} {unit} · {detail}</span></span></label>'.format(
                    name=esc(item["name"]), amount=fmt(item["amount"]), unit=esc(item["unit"]),
                    detail=esc(item.get("detail", ""))
                )
            )

        step_rows = []
        for step in recipe["steps"]:
            timer = ""
            if step.get("timer_minutes"):
                minutes = step["timer_minutes"]
                timer = (
                    f'<div class="timer-control" data-duration-seconds="{minutes * 60}" '
                    f'data-timer-label="{esc(step["title"])}"><button type="button" class="timer-start">'
                    f'Start {minutes} min</button><span class="timer-readout">{minutes}:00</span>'
                    '<button type="button" class="timer-reset">Reset</button>'
                    '<span class="timer-status">Keep this page open for timer alerts.</span></div>'
                )
            step_rows.append(
                f'<li data-task-label="{esc(step["title"])}"><input type="checkbox" '
                f'aria-label="{esc(step["title"])}"><div class="step-text"><span class="step-title">'
                f'{esc(step["title"])}</span><span class="step-detail">{esc(step["text"])}</span>{timer}</div></li>'
            )

        notes = recipe.get("notes", {})
        note_rows = [f"<li>{esc(value)}</li>" for value in notes.values()]
        note_rows.append("<li>Checkoffs stay on this device; report what you cooked and what remains to update inventory.</li>")
        equipment = ", ".join(recipe.get("equipment", [])) or "Basic kitchen equipment"
        values = {
            "state_key": esc(f'{person_id}:{plan["id"]}:{meal["date"]}:{recipe_id}:{content_hash[:12]}'),
            "title": esc(recipe["title"]),
            "eyebrow": esc(f'{meal["date"]} · {person_name} · {meal.get("slot", "meal prep")}'),
            "subtitle": esc(recipe["yield_notes"]),
            "fact_1": esc(f'{recipe.get("total_minutes", "?")} min'), "fact_1_label": "Total time",
            "fact_2": esc(f'{recipe.get("active_minutes", "?")} min'), "fact_2_label": "Active time",
            "fact_3": esc(recipe.get("storage_days", "3–4 days")), "fact_3_label": "Refrigerated",
            "source_summary": f'Recipe revision {recipe["revision"]}; {content_hash[:12]}',
            "plan_link": '<a href="../shopping-list.md">Weekly plan & shopping list</a>',
            "recipe_link": f'<a href="../../recipes/{recipe_id}/recipe.json">Recipe source</a>',
            "use_first": esc(recipe.get("use_first", "Perishables and opened packages")),
            "make_generous": esc(recipe.get("make_generous", "Serve the planned portion")),
            "pack_later": esc(recipe.get("pack_later", "Cool promptly in shallow containers")),
            "ingredient_rows": "\n".join(ingredient_rows), "steps": "\n".join(step_rows),
            "callout": esc(recipe.get("callout", f"Equipment: {equipment}.")), "notes": "\n".join(note_rows),
        }
        page = template_path.read_text()
        for key, value in values.items():
            page = page.replace("{{" + key + "}}", value)
        assert "{{" not in page
        page = page.replace("</head>", f"<!-- source-sha256: {content_hash}; renderer-sha256: {renderer_hash} -->\n</head>")
        destination = ROOT / "generated/cooking" / f"{recipe_id}.html"
        destination.parent.mkdir(exist_ok=True, parents=True)
        destination.write_text(page)
        cards.append({"recipe_id": recipe_id, "source_sha256": content_hash, "card": str(destination.relative_to(ROOT))})

    shopping = []
    for row in demand.values():
        row["purchase_count"] = math.ceil(row["amount"] / row["purchase"]["capacity"])
        used_for = "; ".join(dict.fromkeys(row["recipes"]))
        row["comment"] = f"Used for: {used_for}. Need {fmt(row['amount'])} {row['unit']} total. Check at home before buying."
        shopping.append(row)

    dump(ROOT / "generated/shopping-demand.json", {"plan_id": plan["id"], "inventory_status": "unknown; no deductions made", "items": shopping})
    dump(ROOT / "generated/card-index.json", {"plan_id": plan["id"], "cards": cards})

    lines = [f'# {plan.get("title", "Weekly meal plan")}', "", plan.get("note", ""), "", "## Prep schedule", ""]
    for meal in plan["meals"]:
        recipe = recipes[meal["recipe_id"]]
        lines.append(f'- {meal["date"]}: [{recipe["title"]}](cooking/{recipe["id"]}.html) — {recipe.get("yield_notes", "one batch")}')
    lines += ["", "## Check at home, then buy what is missing", "", "Pantry quantities remain unconfirmed, so no inventory was subtracted.", "", "| Item | Purchase if missing | Exact use |", "| --- | --- | --- |"]
    for row in shopping:
        lines.append(f'| {row["name"]} | {row["purchase_count"]} {row["purchase"]["unit"]}: {row["purchase"]["label"]} | {fmt(row["amount"])} {row["unit"]} |')
    lines += ["", "Refrigerate prepared food within two hours. Use refrigerated cooked portions within four days; freeze later portions promptly.", ""]
    if household.get("shopping", {}).get("list_service") == "samsung_food":
        lines += ["", "Shopping sync status: [integration record](../data/integrations/samsung-food.json).", ""]
    (ROOT / "generated/shopping-list.md").write_text("\n".join(lines))

    catalog = ["# Recipe library", "", "Generated from recipes/*/recipe.json. Ready recipes can drive cards and shopping.", "", "| Recipe | Readiness | Source |", "| --- | --- | --- |"]
    for recipe_id, recipe in recipes.items():
        catalog.append(f'| {recipe["title"]} | {recipe["readiness"]} | [record](../recipes/{recipe_id}/recipe.json) |')
    (ROOT / "generated/recipe-index.md").write_text("\n".join(catalog) + "\n")
    for recipe_id, recipe in recipes.items():
        if recipe.get("readiness") == "ready":
            for warning in timeline_warnings(recipe):
                print(f"WARN {recipe_id}: {warning}")
    print(f'Validated {len(recipes)} recipe records; rendered {len(cards)} cards from {plan_path.name}; consolidated {len(shopping)} grocery rows.')


if __name__ == "__main__":
    main()
