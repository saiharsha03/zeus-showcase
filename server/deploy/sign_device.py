"""Pairing, VPS side (root): sign the phone's certificate request and register its two public keys.

    /opt/zeus/venv/bin/python /opt/zeus/deploy/sign_device.py zeus.csr approval.json > zeus.crt

zeus.csr: the phone's PKCS#10 request (EC P-256 key from its Keystore). approval.json: {"alg", "spki"} of its approval key.
Prints the chain (leaf + CA, PEM). Adds the leaf's SHA-256 to /etc/zeus/devices.json and the approval key to
/etc/zeus/approval-keys.json. Earlier entries stay until removed (`--replace` drops them first). Nothing secret is read
from the phone or written out: the CA key stays in /etc/zeus.
"""
import base64
import datetime as dt
import hashlib
import json
import os
import sys
import time

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, ed25519
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

ETC = os.environ.get("ZEUS_ETC", "/etc/zeus")


def write_json(path, obj, group_mode):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(obj, f, indent=1)
    os.chmod(tmp, group_mode)
    try:
        import grp
        os.chown(tmp, 0, grp.getgrnam("zeus").gr_gid if group_mode == 0o640 else 0)
    except (KeyError, PermissionError, ImportError):
        pass
    os.replace(tmp, path)


def main(argv):
    replace = "--replace" in argv
    csr_path, appr_path = [a for a in argv if not a.startswith("--")][:2]
    csr = x509.load_pem_x509_csr(open(csr_path, "rb").read())
    if not csr.is_signature_valid:
        sys.exit("the request's signature doesn't verify")
    pub = csr.public_key()
    if not isinstance(pub, ec.EllipticCurvePublicKey) or pub.curve.name != "secp256r1":
        sys.exit("expected an EC P-256 key")
    appr = json.load(open(appr_path))
    spki = base64.b64decode(appr["spki"])
    akey = serialization.load_der_public_key(spki)
    if appr["alg"] == "ed25519" and not isinstance(akey, ed25519.Ed25519PublicKey) or \
            appr["alg"] == "es256" and not (isinstance(akey, ec.EllipticCurvePublicKey) and akey.curve.name == "secp256r1") or \
            appr["alg"] not in ("ed25519", "es256"):
        sys.exit("approval key and alg don't match")

    ca_cert = x509.load_pem_x509_certificate(open(f"{ETC}/ca.crt", "rb").read())
    ca_key = serialization.load_pem_private_key(open(f"{ETC}/ca.key", "rb").read(), None)
    now = dt.datetime.now(dt.timezone.utc)
    cert = (x509.CertificateBuilder()
            .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "owner-fold")]))
            .issuer_name(ca_cert.subject).public_key(pub).serial_number(x509.random_serial_number())
            .not_valid_before(now - dt.timedelta(minutes=5)).not_valid_after(now + dt.timedelta(days=730))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(x509.KeyUsage(True, False, False, False, False, False, False, False, False), critical=True)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.CLIENT_AUTH]), critical=False)
            .sign(ca_key, hashes.SHA256()))
    der = cert.public_bytes(serialization.Encoding.DER)
    fp = hashlib.sha256(der).hexdigest()

    devices = {"devices": []} if replace else json.load(open(f"{ETC}/devices.json"))
    devices["devices"].append({"sha256": fp, "name": "owner-fold", "added": int(time.time()), "not_after": int(cert.not_valid_after_utc.timestamp())})
    write_json(f"{ETC}/devices.json", devices, 0o640)
    keys = {"keys": []} if replace else json.load(open(f"{ETC}/approval-keys.json"))
    kid = hashlib.sha256(spki).hexdigest()[:16]
    keys["keys"] = [k for k in keys["keys"] if k.get("kid") != kid] + [{"kid": kid, "alg": appr["alg"], "spki": appr["spki"], "added": int(time.time())}]
    write_json(f"{ETC}/approval-keys.json", keys, 0o644)

    sys.stdout.write(cert.public_bytes(serialization.Encoding.PEM).decode() + ca_cert.public_bytes(serialization.Encoding.PEM).decode())
    sys.stderr.write(f"signed: device {fp[:16]}..., approval key {kid} ({appr['alg']})\n")


if __name__ == "__main__":
    main(sys.argv[1:])
