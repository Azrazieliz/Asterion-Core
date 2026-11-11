"""Owner-only local signing and Asterion Control transport of a verified release.
The original signing key arrives only in an AES-GCM envelope encrypted to the
existing Windows worker's ephemeral X25519 public key. Never commit passwords.
"""
import os,base64,json,zlib,subprocess,hashlib,shutil,socket,ipaddress
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric import x25519
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
import requests
folder=Path(os.environ['LOCALAPPDATA'])/'AsterionCoreInstallerTemp'
work=Path('D:/AsterionCoreInstaller')
source=work/'AsterionCore-v2.0.36-Alternate-UNSIGNED.apk'
assert source.is_file() and source.stat().st_size>300_000_000,'unsigned APK not ready'
envelope=json.loads((folder/'envelope.json').read_text())
priv=x25519.X25519PrivateKey.from_private_bytes((folder/'ephemeral_x25519.private').read_bytes())
sender=x25519.X25519PublicKey.from_public_bytes(base64.b64decode(envelope['sender_pub']))
key=HKDF(algorithm=hashes.SHA256(),length=32,salt=base64.b64decode(envelope['salt']),info=b'AsterionCore-v2.0.36-single-use-signing').derive(priv.exchange(sender))
secret=json.loads(zlib.decompress(AESGCM(key).decrypt(base64.b64decode(envelope['nonce']),base64.b64decode(envelope['ct']),b'AsterionCore-v2.0.36')))
assert secret['expected_cert'].lower()=='4d652e25dfef5d90a3494a993c0e3d60229790d07017297d540c87621508a94e','original signer mismatch'
credentials=secret['credentials']
keystore=folder/'core-single-use-release.p12'
keystore.write_bytes(base64.b64decode(secret['keystore_b64']))
try:
    signed=work/'AsterionCore-v2.0.36-Alternate-SIGNED.apk'
    signer=Path(os.environ['LOCALAPPDATA'])/'Android/Sdk/build-tools/35.0.1/apksigner.bat'
    assert signer.is_file(),'Android SDK apksigner missing'
    childenv=os.environ.copy()
    childenv['CORE_KS_PASS']=credentials['store_password']
    childenv['CORE_KEY_PASS']=credentials['key_password']
    subprocess.run([str(signer),'sign','--ks',str(keystore),'--ks-type','PKCS12','--ks-key-alias',credentials['alias'],'--ks-pass','env:CORE_KS_PASS','--key-pass','env:CORE_KEY_PASS','--out',str(signed),str(source)],env=childenv,check=True,stdout=subprocess.DEVNULL)
    result=subprocess.run([str(signer),'verify','--verbose','--print-certs',str(signed)],capture_output=True,text=True,check=True)
    actual=next((line.split(':',1)[1].strip().lower().replace(':','') for line in result.stdout.splitlines() if line.startswith('Signer #1 certificate SHA-256 digest:')),None)
    assert actual==secret['expected_cert'].lower(),'signed APK cert fingerprint mismatch'
    assert signed.is_file() and signed.stat().st_size>300_000_000
    dest=Path.home()/'Downloads'/'Asterion Control'/'AsterionCore-v2.0.36-Alternate-SIGNED.apk'
    dest.parent.mkdir(parents=True,exist_ok=True)
    assert shutil.disk_usage(dest.parent).free>signed.stat().st_size+50_000_000,'Windows C: lacks room for transfer staging'
    shutil.copyfile(signed,dest)
    with dest.open('rb') as file:
        sha=hashlib.file_digest(file,'sha256').hexdigest()
    tokens=[Path(os.environ['LOCALAPPDATA'])/'AsterionControl/control-token.txt',Path(os.environ['LOCALAPPDATA'])/'AsterionRemote/token.txt']
    token=next((p.read_text().strip() for p in tokens if p.is_file()),None)
    assert token and len(token)>30,'authorized Control token unavailable'
    # Existing Control worker may bind its API to the actual local LAN IP
    # rather than loopback. Discover only addresses assigned to this machine;
    # never transmit its bearer token through an HTTP proxy or third-party host.
    addresses=['127.0.0.1']
    for record in socket.getaddrinfo(socket.gethostname(),None,socket.AF_INET):
        address=record[4][0]
        if ipaddress.ip_address(address).is_private and address not in addresses:
            addresses.append(address)
    session=requests.Session()
    session.trust_env=False
    job=None
    for local_address in addresses:
        try:
            response=session.post(
                f'http://{local_address}:8911/api/v1/phone/transfers',
                headers={'Authorization':'Bearer '+token},
                json={'pc_path':str(dest),'name':dest.name},timeout=35)
        except requests.exceptions.ConnectionError:
            continue
        # Authenticated HTTP errors must be surfaced rather than retried
        # against a different interface or mistaken for a successful transfer.
        response.raise_for_status()
        job=response.json()
        break
    if job is None:
        raise RuntimeError('Authorized Asterion Control transfer listener unreachable')
    if not job.get('id'):
        raise RuntimeError('Asterion Control did not provide a transfer job ID')
    print('SIGNED_ORIGINAL_CERT_VERIFIED=True')
    print('SIGNED_APK_SHA256='+sha)
    print('SIGNED_APK_BYTES='+str(dest.stat().st_size))
    print('PHONE_TRANSFER_ID='+job['id'])
finally:
    keystore.unlink(missing_ok=True)
    for name in ['ephemeral_x25519.private','envelope.json','envelope.fixed1','envelope.fixed2','envelope.fixed3','envelope.part1','envelope.part2','envelope.part3']:
        (folder/name).unlink(missing_ok=True)
