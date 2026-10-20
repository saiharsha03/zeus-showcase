"""Iris's Moneta ops: the schema, her CLI, and the gateway intake. Run: python -m pytest server/tests -q"""
import importlib.machinery
import importlib.util
import json
import os
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "lib"))
import moneta_ops as mo  # noqa: E402


def load(name, path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    return mod


cli = load("zeus_moneta", ROOT / "bin" / "zeus-moneta")
SPEC = {"id": "dining-trend", "type": "line", "title": "Eating out by month", "query": {"by": "month", "months": 6, "categories": ["Eating out"]}}


def test_every_op_checks_its_arguments():
    assert mo.check("add", {"date": "2026-10-19", "amount": 47.82, "merchant": "Grocer Mart", "last4": "1234"})["amount"] == 47.82
    assert mo.check("edit", {"key": "iris:812", "fields": {"category": "Groceries"}}) == {"key": "iris:812", "fields": {"category": "Groceries"}}
    assert mo.check("merge", {"keep": "iris:1", "drop": ["iris:2"]})["drop"] == ["iris:2"]
    assert mo.check("budget", {"category": "Groceries", "amount": None})["amount"] is None
    assert mo.check("chart_put", {"spec": SPEC})["spec"]["id"] == "dining-trend"
    bad = [
        ("add", {"date": "2026-10-19", "amount": 5, "merchant": "Card 4111111111111111"}),   # full card number
        ("add", {"date": "2026-10-19", "amount": 5, "merchant": "x", "last4": "31005"}),     # not four digits
        ("add", {"date": "yesterday", "amount": 5, "merchant": "x"}),
        ("add", {"date": "2026-10-19", "amount": -5, "merchant": "x"}),
        ("edit", {"key": "iris:1", "fields": {"balance": 10}}),
        ("merge", {"keep": "iris:1", "drop": ["iris:1"]}),
        ("delete", {"key": "../etc"}),
        ("pay", {"to": "landlord"}),                                                        # money never moves
        ("chart_put", {"spec": SPEC | {"html": "<b>x</b>"}}),
        ("chart_put", {"spec": SPEC | {"type": "pie3d"}}),
        ("chart_put", {"spec": SPEC | {"query": {"by": "month", "split": "month"}}}),
        ("chart_put", {"spec": SPEC | {"query": {"months": 99}}}),
        ("chart_put", {"spec": SPEC | {"title": "x" * 61}}),
        ("chart_put", {"spec": SPEC | {"subtitle": "y" * 5000}}),
    ]
    for op, args in bad:
        with pytest.raises(mo.Invalid):
            mo.check(op, args)


def test_the_cli_queues_one_op_and_says_only_what_happened(tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(cli, "SPOOL", str(tmp_path))
    assert cli.main(["add", '{"date":"2026-10-19","amount":12.5,"merchant":"Corner Cafe","category":"Eating out"}']) == 0
    out = json.loads(capsys.readouterr().out)
    assert out["queued"] and out["key"].startswith("iris:op-")
    env = json.loads((tmp_path / f"{out['op_id']}.json").read_text())
    assert env["op"] == "add" and env["args"]["key"] == out["key"] and mo.envelope_ok(env)
    assert cli.main(["budget", "Groceries", "none"]) == 0
    assert cli.main(["merge", "iris:1", "iris:2", "iris:3"]) == 0
    capsys.readouterr()
    assert cli.main(["edit", "iris:9", '{"merchant":"acct 123456789"}']) == 2
    assert "card or account number" in json.loads(capsys.readouterr().out)["error"]
    assert len(list(tmp_path.glob("*.json"))) == 3
    assert cli.main(["schema"]) == 0 and "chart" in json.loads(capsys.readouterr().out)


def test_the_gateway_keeps_only_irises_ops(tmp_path, monkeypatch):
    pytest.importorskip("pwd")
    monkeypatch.setenv("ZEUS_STATE", str(tmp_path / "state"))
    monkeypatch.setenv("ZEUS_MONETA_SPOOL", str(tmp_path / "spool"))
    import pwd
    me = pwd.getpwuid(os.getuid()).pw_name
    monkeypatch.setenv("ZEUS_IRIS_USER", me)            # the test's own files stand in for openclaw's
    (tmp_path / "spool").mkdir()
    sys.path.insert(0, str(ROOT))
    for m in [m for m in sys.modules if m.startswith("gateway")]:
        del sys.modules[m]
    from gateway import moneta as gm

    class Audit:
        rows = []
        def audit(self, a, d): self.rows.append((a, d))

    woke = []
    g = gm.Moneta(Audit(), wake=lambda: woke.append(1))
    monkeypatch.setattr(cli, "SPOOL", str(tmp_path / "spool"))
    cli.main(["recategorize", "iris:812", "Groceries"])
    env = json.loads(next((tmp_path / "spool").glob("*.json")).read_text())
    env["from"] = "atlas"
    (tmp_path / "spool" / f"{env['op_id']}.json").write_text(json.dumps(env))
    (tmp_path / "spool" / "bad.json").write_text(json.dumps({"v": 1, "op": "pay"}))
    assert g.intake() == 1 and woke == [1]
    ops = g.ops()
    assert [o["from"] for o in ops] == ["iris"]          # provenance is set by the gateway, not the sender
    assert g.ack([ops[0]["op_id"]]) == 1 and g.ops() == []
    assert ("moneta.bad", "bad.json") in Audit.rows
    if os.getuid() == 0:
        return
    monkeypatch.setattr(g, "iris_uid", os.getuid() + 1)   # someone else's file is ignored
    cli.main(["delete", "iris:1"])
    assert g.intake() == 0 and any(a == "moneta.foreign" for a, _ in Audit.rows)


def test_symlinked_fifo_and_foreign_drops_are_refused(tmp_path, monkeypatch):
    """A symlink to one of Iris's files, a FIFO, or a file someone else owns never becomes an op from Iris."""
    pytest.importorskip("pwd")
    import pwd
    monkeypatch.setenv("ZEUS_STATE", str(tmp_path / "state"))
    monkeypatch.setenv("ZEUS_MONETA_SPOOL", str(tmp_path / "spool"))
    monkeypatch.setenv("ZEUS_IRIS_USER", pwd.getpwuid(os.getuid()).pw_name)
    for d in ("spool", "elsewhere"):
        (tmp_path / d).mkdir()
    sys.path.insert(0, str(ROOT))
    for m in [m for m in sys.modules if m.startswith("gateway")]:
        del sys.modules[m]
    from gateway import moneta as gm

    class Audit:
        rows = []
        def audit(self, a, d): self.rows.append((a, d))

    g = gm.Moneta(Audit())
    monkeypatch.setattr(cli, "SPOOL", str(tmp_path / "elsewhere"))
    cli.main(["delete", "iris:1"])
    real = next((tmp_path / "elsewhere").glob("*.json"))          # owned by "Iris", outside the spool
    (tmp_path / "spool" / "link.json").symlink_to(real)
    os.mkfifo(tmp_path / "spool" / "pipe.json")
    assert g.intake() == 0 and g.ops() == []
    assert {d.split(":")[0] for a, d in Audit.rows if a == "moneta.bad"} == {"link.json", "pipe.json"}
    assert real.exists() and list((tmp_path / "spool").iterdir()) == []
    foreign = tmp_path / "spool" / real.name
    foreign.write_bytes(real.read_bytes())
    if os.getuid() == 0:
        os.chown(foreign, pwd.getpwnam("nobody").pw_uid, -1)      # really someone else's
    else:
        monkeypatch.setattr(g, "iris_uid", os.getuid() + 1)
    assert g.intake() == 0 and ("moneta.foreign", real.name[:40]) in Audit.rows and not foreign.exists()
