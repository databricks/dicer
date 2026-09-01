# Dicer project site

Static source for the Dicer landing page (based on the top-level `README.md`).

Preview locally:

```bash
cd site
python3 -m http.server 8000
# open http://localhost:8000
```

To publish, enable GitHub Pages on this repo with source set to the `master`
branch, `/site` folder (Settings → Pages → Build and deployment → Deploy from
a branch → `master` / `/site`). The page will be served at
<https://databricks.github.io/dicer/>.
