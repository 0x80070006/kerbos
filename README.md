# Kerbos

Authentificateur OTP (TOTP / HOTP / Steam) pour Android, inspiré d'Aegis, avec **synchronisation automatique et fusion multi-appareils via Nextcloud (WebDAV)**.

## Fonctionnalités

- Coffre local chiffré AES-256-GCM (clé maître aléatoire, déverrouillée par mot de passe PBKDF2-SHA256 210 000 itérations et/ou biométrie forte via Android Keystore).
- **Biométrie par défaut** : si elle est activée, l'invite biométrique s'ouvre automatiquement à chaque lancement / retour dans l'app (désactivable dans Paramètres).
- Ajout par QR code (caméra), par image (capture d'écran), saisie manuelle, liens `otpauth://` et exports Google Authenticator (`otpauth-migration://`).
- Bibliothèque de ~3 500 icônes de marques (Simple Icons, CC0) : l'icône correspondant à l'émetteur du QR code est **sélectionnée automatiquement et suggérée en premier**.
- Favoris, groupes, recherche, codes masqués (option), copie au toucher (presse-papier marqué sensible, effacé après 45 s), blocage des captures d'écran, verrouillage automatique.

## Synchronisation Nextcloud

Fichier unique `<dossier>/kerbos-vault.json` sur votre Nextcloud, **chiffré de bout en bout** avec une *phrase de synchronisation* commune à tous vos appareils (Nextcloud ne voit jamais les secrets).

Fusion sans perte :
- chaque compte a un identifiant unique et un horodatage de modification ;
- les suppressions sont propagées via des « pierres tombales » (conservées 180 jours) ;
- à chaque synchro : téléchargement → fusion compte par compte (la modification la plus récente gagne) → envoi avec `If-Match` (ETag). Si un autre appareil a écrit entre-temps, le cycle recommence : aucun appareil n'écrase les ajouts d'un autre.

Déclenchement : au déverrouillage, ~1 s après chaque modification, toutes les 60 s tant que l'app est déverrouillée, et au retour au premier plan.

Configuration : Paramètres → Synchronisation → adresse du serveur, utilisateur, **mot de passe d'application** Nextcloud (Paramètres personnels → Sécurité), dossier, phrase de synchronisation.

Les CA installées par l'utilisateur sont acceptées (Nextcloud auto-hébergé avec certificat interne). HTTPS obligatoire.

## Compiler

Toolchain portable dans `.toolchain/` (JDK 17, SDK Android 34, Gradle 8.9).

```bash
./build.sh assembleRelease
```

APK : `app/build/outputs/apk/release/app-release.apk`.

**Important** : conservez `keystore/kerbos-release.jks` et `keystore.properties` (hors dépôt). Sans eux, impossible de publier une mise à jour installable par-dessus la version actuelle.
