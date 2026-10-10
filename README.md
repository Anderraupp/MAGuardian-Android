# MAGuardian-Android
M&amp;A Guardian - App Android nativo de proteção contra pop-ups e adware

## Atualizar e abrir

Na pasta do seu clone, com as alterações locais preservadas:

```bash
git pull origin main
```

Se o Git apontar alterações locais ou conflito, não use `reset --hard` nem apague
arquivos: preserve suas alterações antes de continuar.

Abra no Android Studio a **raiz com `settings.gradle` e `gradlew`**. O módulo
atual é `app/`. A subpasta parcial antiga `android-native/` foi preservada para
não apagar arquivos existentes, mas não é usada pelo build atual.

- Android Studio compatível com AGP 8.9.1 (Meerkat ou mais recente).
- JDK 17, SDK Android 36 e Build Tools 35.0.0.
- Versão preparada para publicação: **1.6.4 / código 42**.
- `git pull` atualiza os fontes; não atualiza sozinho o aplicativo instalado.
  Compile e use seu procedimento habitual de instalação/assinatura, sem
  desinstalar a instalação de uso apenas para rodar testes.

Para enviar ao Google Play, gere **Android App Bundle** em
**Build → Generate Signed Bundle / APK**, selecione `release` e use a mesma
chave de upload das versões anteriores. O código 42 precisa ser maior que
todos os códigos já enviados à sua Play Console. A chave privada do catálogo
não é a chave de upload e não é necessária no Android Studio.

## Catálogo autenticado e offline

O Git contém somente a **chave pública**, o catálogo assinado e o bootstrap
offline. A chave privada não é necessária para compilar ou usar o aplicativo.
O catálogo inicial é versão **3**, independente da versão do APK.

Em um clone novo, as ferramentas de publicação requerem Node.js 20 e:

```bash
npm install
npm run catalog:check-trust
npm run catalog:test
```

Esses checks não precisam da chave privada. Para publicar versões futuras,
siga `threat-catalog/README.md`, em ambiente de assinatura controlado pelo
operador; nunca adicione a chave privada ao Git nem aos Secrets do GitHub.
Não repita o provisionamento inicial nem gere outra chave para um simples pull.

## Compilação e testes

Veja `COMO_ABRIR.md`, `COMO_COMPILAR.md` e `VALIDACAO_CATALOGO_ANDROID.md`.
O workflow Build APK é **manual**, gera somente debug e não cria release/tag
nem publica na Play Store. O workflow de validação usa instalação descartável
com outro applicationId e nunca interfere nos dados do aplicativo de uso.
