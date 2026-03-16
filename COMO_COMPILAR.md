# Como Compilar o M&A Guardian no Android Studio

## Pré-requisitos
- Android Studio Hedgehog (2023.1) ou mais recente
- Android SDK 34 instalado
- JDK 17 (incluído no Android Studio moderno)

---

## Passo 1 — Baixar o projeto do Replit

No Replit, clique nos **3 pontos** (menu) → **Download as ZIP**.
Extraia o arquivo e localize a pasta **`android-native/`**.

---

## Passo 2 — Abrir no Android Studio

1. Abra o **Android Studio**
2. Clique em **"Open"** (não "New Project")
3. Navegue até a pasta `android-native/` e selecione ela
4. Aguarde o Gradle sincronizar (pode demorar 2–5 minutos na primeira vez)

---

## Passo 3 — Corrigir o SDK Path (se necessário)

Se aparecer erro `SDK location not found`:
1. Vá em **File → Project Structure → SDK Location**
2. Aponte para onde seu Android SDK está instalado
   - Windows: `C:\Users\SEU_USUARIO\AppData\Local\Android\Sdk`
   - Mac: `/Users/SEU_USUARIO/Library/Android/sdk`

---

## Passo 4 — Gerar o APK de Debug (para testar)

1. No menu superior: **Build → Build Bundle(s) / APK(s) → Build APK(s)**
2. Aguarde a compilação
3. Clique em **"locate"** no aviso que aparece no canto inferior direito
4. O APK estará em: `android-native/app/build/outputs/apk/debug/app-debug.apk`

---

## Passo 5 — Instalar no celular

### Opção A — Via USB (mais fácil para testar)
1. Ative **Opções do Desenvolvedor** no celular:
   - Configurações → Sobre o telefone → toque 7x em "Número da versão"
2. Ative **Depuração USB** nas Opções do Desenvolvedor
3. Conecte o celular via USB
4. No Android Studio: **Run → Run 'app'** (ícone ▶)

### Opção B — Instalar o APK manualmente
1. Copie o `app-debug.apk` para o celular
2. Abra o gerenciador de arquivos no celular
3. Toque no APK → Instalar
4. Se aparecer "Fontes desconhecidas", ative nas configurações de segurança

---

## Passo 6 — Conceder permissões obrigatórias

Após instalar, o app vai pedir duas permissões essenciais:

### 1. Acesso ao Uso de Apps (OBRIGATÓRIO para detecção real)
- Configurações → Privacidade → Gerenciar permissões de uso de dados
- Ou: Configurações → Apps → Acesso especial → Dados de uso
- Ative o **M&A Guardian**

### 2. Serviço de Acessibilidade (OBRIGATÓRIO)
- Configurações → Acessibilidade → Apps instalados
- Toque em **M&A Guardian** → Ative

### 3. Exibir sobre outros apps (Opcional — para overlay)
- Configurações → Apps → M&A Guardian → Exibir sobre outros apps
- Ative

---

## Passo 7 — Testar a detecção

Com as permissões concedidas:
1. Abra o **M&A Guardian** → toque **"Escanear Agora"**
2. O app vai verificar todos os aplicativos instalados contra a base de malware
3. Para testar detecção em tempo real: instale um dos apps da base de malware ou use um app que muda rapidamente entre telas

---

## Gerar APK de Release (para publicar na Play Store)

1. **Build → Generate Signed Bundle / APK**
2. Escolha **APK**
3. Crie um novo keystore (guarde bem! Você precisará dele sempre)
4. Preencha alias, senhas
5. Escolha **release**
6. O APK assinado estará em: `app/release/app-release.apk`

> ⚠️ Anote o SHA-256 do seu keystore! Você precisará dele para configurar o Digital Asset Links (assetlinks.json) e o TWA.
> Para obter: `keytool -list -v -keystore seu_keystore.jks`

---

## Problemas comuns

| Erro | Solução |
|------|---------|
| `Gradle sync failed` | File → Invalidate Caches → Restart |
| `minSdk version too low` | Já está configurado como API 26 (Android 8) |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Desinstale a versão anterior do app |
| `Missing SDK` | Instale o Android SDK 34 via SDK Manager |
