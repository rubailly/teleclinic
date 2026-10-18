# eBuzima stand-in: ERPNext with the Frappe Healthcare app, the same stack as the national eBuzima (ebuzima.moh.gov.rw).
#
# The app is installed at build time (never at runtime), pinned to a commit,
# so every container in the stack (backend, workers, scheduler, websocket,
# frontend) runs the same code. It is downloaded as a tarball of that commit
# from the GitHub API rather than cloned: the content is identical, and it
# avoids git clones, which fail on some networks.
FROM frappe/erpnext:v15.121.4

ARG HEALTHCARE_COMMIT=41f05060a69fbefe0f91ddad8989ab8442eec1c6
ARG HEALTHCARE_TARBALL=https://api.github.com/repos/frappe/healthcare/tarball/${HEALTHCARE_COMMIT}

USER frappe
WORKDIR /home/frappe/frappe-bench

RUN set -e; \
    for attempt in 1 2 3 4 5; do \
      curl -fsSL -o /tmp/healthcare.tgz "$HEALTHCARE_TARBALL" && break; \
      [ "$attempt" = 5 ] && { echo "could not download $HEALTHCARE_TARBALL"; exit 1; }; \
      echo "download failed (attempt $attempt), retrying"; sleep 15; \
    done; \
    mkdir apps/healthcare; \
    tar xzf /tmp/healthcare.tgz -C apps/healthcare --strip-components=1; \
    rm /tmp/healthcare.tgz; \
    ./env/bin/pip install --no-cache-dir -e apps/healthcare; \
    # the asset build finds apps through sites/apps.txt
    ls -1 apps > sites/apps.txt; \
    bench build --app healthcare
