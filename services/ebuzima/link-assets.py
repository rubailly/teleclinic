"""Puts every installed app's built assets where Frappe serves them at runtime (run once, at image build).

At runtime sites/assets is a link to /home/frappe/frappe-bench/assets, the folder the base image filled for Frappe and
ERPNext. Building Healthcare into the image wrote its bundle under the build-time sites/assets instead, so the browser
got a 404 for healthcare.bundle.js. This links each app's public folder into the runtime folder and adds its built
bundles to the manifests; it never removes or rewrites an existing entry.
"""
import json
import os
import re

BENCH = "/home/frappe/frappe-bench"
ASSETS = os.path.join(BENCH, "assets")
BUNDLE = re.compile(r"^(?P<name>.+)\.bundle\.[A-Z0-9]+\.(?P<ext>js|css)$")

apps = [a.strip() for a in open(os.path.join(BENCH, "sites", "apps.txt")) if a.strip()]
manifests = {name: os.path.join(ASSETS, name) for name in ("assets.json", "assets-rtl.json")}
data = {k: (json.load(open(p)) if os.path.exists(p) else {}) for k, p in manifests.items()}
added = []
for app in apps:
    public = os.path.join(BENCH, "apps", app, app, "public")
    if not os.path.isdir(public):
        continue
    link = os.path.join(ASSETS, app)
    if not os.path.lexists(link):
        os.symlink(public, link)
        added.append(f"link {app}")
    for kind in ("js", "css", "css-rtl"):
        folder = os.path.join(public, "dist", kind)
        if not os.path.isdir(folder):
            continue
        target = data["assets-rtl.json" if kind == "css-rtl" else "assets.json"]
        for f in sorted(os.listdir(folder)):
            m = BUNDLE.match(f)
            # Frappe merges both manifests into one, so right-to-left entries carry an rtl_ prefix
            key = (("rtl_" if kind == "css-rtl" else "") + f"{m['name']}.bundle.{m['ext']}") if m else None
            if key and key not in target:
                target[key] = f"/assets/{app}/dist/{kind}/{f}"
                added.append(key)
for k, p in manifests.items():
    json.dump(data[k], open(p, "w"), indent=4)
print("assets: " + (", ".join(added) if added else "all apps already linked"))
