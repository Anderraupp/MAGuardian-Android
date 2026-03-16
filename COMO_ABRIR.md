# Como Abrir o M&A Guardian no Android Studio

## Pré-requisitos

1. **Android Studio** (versão Hedgehog ou mais recente) — baixe em: https://developer.android.com/studio
2. **JDK 17** ou superior (já vem com o Android Studio)

---

## Passos para abrir

1. Abra o Android Studio
2. Clique em **"Open"**
3. Navegue até a pasta **`android-native/`** deste projeto
4. Clique em **OK**
5. Aguarde o Gradle sincronizar (pode demorar alguns minutos na primeira vez)

---

## Como obter a API Key gratuita do VirusTotal

1. Acesse: https://www.virustotal.com
2. Crie uma conta gratuita
3. Vá em **"API Key"** no seu perfil
4. Copie a chave
5. No Android Studio, abra `app/src/main/java/com/maguardian/security/util/PrefsHelper.kt`
6. Adicione: `const val VIRUSTOTAL_API_KEY = "sua_chave_aqui"`

**Limite gratuito:** 500 requisições/dia (mais que suficiente)

---

## Permissões que o usuário precisa conceder

O app pedirá estas permissões na primeira abertura:

| Permissão | Onde ativar | Por quê |
|-----------|-------------|---------|
| **Acesso ao Histórico de Uso** | Configurações > Apps > Acesso especial > Acesso de uso | Para detectar qual app está em foreground (pop-ups) |
| **Serviço de Acessibilidade** | Configurações > Acessibilidade > M&A Guardian | Para detectar mudanças de janela em tempo real |
| **Exibir sobre outros apps** | Configurações > Apps > M&A Guardian > Permissões | Opcional: para exibir alertas em overlay |

---

## Como gerar o APK para distribuição

1. No Android Studio: **Build > Generate Signed Bundle/APK**
2. Escolha **APK**
3. Crie ou selecione seu keystore
4. Build Type: **Release**
5. O APK ficará em: `app/release/app-release.apk`

---

## Como publicar na Play Store

1. Crie conta de desenvolvedor em: https://play.google.com/console
2. Taxa única: **U$ 25**
3. Crie um novo app
4. Faça upload do APK/AAB gerado
5. Configure a ficha do app em português
6. Envie para revisão (normalmente 1-3 dias)

---

## Configurar TWA (Trusted Web Activity) para PWA na Play Store

Se quiser publicar o **app web (PWA)** na Play Store sem código nativo:

1. Instale: `npm install -g @bubblewrap/cli`
2. Execute: `bubblewrap init --manifest https://SEU-DOMINIO.replit.app/manifest.json`
3. Execute: `bubblewrap build`
4. O arquivo `.aab` gerado pode ser enviado direto para a Play Store

**Importante:** Antes de publicar via TWA, atualize o `/.well-known/assetlinks.json` com o SHA-256 do seu keystore:
```bash
keytool -list -v -keystore my-release-key.keystore
```
Cole o fingerprint SHA-256 no campo `sha256_cert_fingerprints` em `server/routes.ts`

---

## Estrutura do projeto Android

```
android-native/
├── app/
│   └── src/main/
│       ├── java/com/maguardian/security/
│       │   ├── data/
│       │   │   └── MalwareDatabase.kt      ← 50 apps maliciosos conhecidos
│       │   ├── service/
│       │   │   └── PopupDetectorService.kt ← Detecção REAL com UsageStatsManager
│       │   ├── receiver/
│       │   │   ├── BootReceiver.kt         ← Auto-início no boot
│       │   │   └── UninstallReceiver.kt    ← Botão "Desinstalar" na notificação
│       │   ├── util/
│       │   │   ├── PrefsHelper.kt          ← Persistência de dados
│       │   │   └── PermissionHelper.kt     ← Verificação de permissões
│       │   └── ui/
│       │       └── MainActivity.kt         ← Tela principal
│       ├── res/
│       │   ├── layout/
│       │   │   ├── activity_main.xml       ← Layout da tela principal
│       │   │   └── item_threat.xml         ← Card de ameaça
│       │   └── values/
│       │       ├── colors.xml              ← Vermelho e dourado M&A Guardian
│       │       ├── strings.xml             ← Textos em português
│       │       └── themes.xml              ← Tema escuro
│       └── AndroidManifest.xml             ← Permissões e componentes
├── build.gradle
├── settings.gradle
└── COMO_ABRIR.md                           ← Este arquivo
```
