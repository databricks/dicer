# Dicer project site

Static source for the Dicer landing page (based on the `README.md` on
`master`). This branch (`gh-pages`) is the source for GitHub Pages and
contains only the site assets — no other repo content.

Preview locally:

```bash
python3 -m http.server 8000
# open http://localhost:8000
```

To publish, enable GitHub Pages on this repo with source set to the
`gh-pages` branch, `/` (root) folder (Settings → Pages → Build and
deployment → Deploy from a branch → `gh-pages` / `/`). The page will be
served at <https://databricks.github.io/dicer/>.
