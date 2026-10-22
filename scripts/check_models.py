#!/usr/bin/env python3
"""Vérifie le catalogue de modèles contre Hugging Face.

Pour chaque fichier : existence, taille et SHA-256 (oid LFS, ou calculé pour les petits
fichiers non-LFS). Écrit un catalogue résolu (tailles, SHA-256, révision épinglée) et
échoue si une valeur déclarée ne correspond pas.
"""
import argparse
import hashlib
import json
import sys
import urllib.request

API = "https://huggingface.co/api/models"


def get_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": "speech-ci"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def sha256_of_url(url):
    req = urllib.request.Request(url, headers={"User-Agent": "speech-ci"})
    h = hashlib.sha256()
    n = 0
    with urllib.request.urlopen(req, timeout=120) as r:
        while chunk := r.read(1 << 20):
            h.update(chunk)
            n += len(chunk)
    return h.hexdigest(), n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", default="app/src/main/assets/models.json")
    ap.add_argument("--out", default="models.resolved.json")
    args = ap.parse_args()

    catalog = json.load(open(args.catalog, encoding="utf-8"))
    errors, warnings = [], []
    for m in catalog["models"]:
        if all(f.get("url") for f in m["files"]):
            # Fichiers hors Hugging Face (ex. VAD sur une release GitHub) : téléchargés et hachés.
            print(f"== {m['id']}  (URL directes)")
            for f in m["files"]:
                sha, size = sha256_of_url(f["url"])
                print(f"   {f['path']}: {size} octets, sha256 {sha}")
                if f.get("size") and f["size"] != size:
                    errors.append(f"{m['id']}: taille de {f['path']} = {size}, catalogue = {f['size']}")
                if f.get("sha256") and f["sha256"].lower() != sha:
                    errors.append(f"{m['id']}: sha256 de {f['path']} = {sha}, catalogue = {f['sha256']}")
            continue
        repo, rev = m["repo"], m.get("revision", "main")
        info = get_json(f"{API}/{repo}/revision/{rev}")
        commit = info["sha"]
        tree = {e["path"]: e for e in get_json(f"{API}/{repo}/tree/{commit}")}
        print(f"== {m['id']}  ({repo}@{commit[:10]})")
        for f in m["files"]:
            e = tree.get(f["path"])
            if e is None:
                errors.append(f"{m['id']}: {f['path']} absent de {repo}")
                print(f"   MANQUANT {f['path']}")
                continue
            if "lfs" in e:
                size, sha = e["lfs"]["size"], e["lfs"]["oid"]
            else:
                sha, size = sha256_of_url(f"https://huggingface.co/{repo}/resolve/{commit}/{f['path']}")
            print(f"   {f['path']}: {size} octets, sha256 {sha}")
            if f.get("size") and f["size"] != size:
                errors.append(f"{m['id']}: taille de {f['path']} = {size}, catalogue = {f['size']}")
            if f.get("sha256") and f["sha256"].lower() != sha:
                errors.append(f"{m['id']}: sha256 de {f['path']} = {sha}, catalogue = {f['sha256']}")
            if not f.get("sha256"):
                warnings.append(f"{m['id']}: {f['path']} sans sha256 dans le catalogue")
            f["size"], f["sha256"] = size, sha
        if rev != commit:
            warnings.append(f"{m['id']}: révision '{rev}' non épinglée (commit {commit})")
        m["revision"] = commit

    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(catalog, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    for w in warnings:
        print("AVERTISSEMENT:", w)
    for e in errors:
        print("ERREUR:", e)
    print(f"Catalogue résolu écrit dans {args.out}")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
