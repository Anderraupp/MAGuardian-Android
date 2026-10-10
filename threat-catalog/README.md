# Base adicional de ameaças do M&A Guardian

## Uso e limites

O Android faz **GET sem parâmetros, autenticação de usuário ou inventário**
para `https://raw.githubusercontent.com/Anderraupp/MAGuardian-Android/main/threat-catalog/catalog.json`.
Não envia pacotes, hashes, resultados de scans, telefones ou identificadores.
O provedor de hospedagem recebe necessariamente o IP/conexão HTTP normal.
O arquivo contém exclusivamente metadados. Não contém APKs nem links de download.

Há uma **instalação inicial necessária do código Android atualizado**. APKs
antigos não podem adquirir essa capacidade sozinhos. Nenhuma versão, tag,
release ou publicação na loja é criada por este trabalho.

O catálogo adicional nunca altera a base embarcada, os prefixos confiáveis,
as isenções existentes ou a escolha **Manter**. Uma entrada adicional só
detecta quando pacote **e SHA-256 do APK instalado** correspondem. Um pacote
com mesmo nome e binário diferente não é confirmado por este catálogo.
O APK é lido localmente; jamais transferido. APKs split/reempacotados sem
correspondência exata não são confirmados (limite conservador intencional).

## Hospedagem e autenticação

O backend existente é Express/PostgreSQL, com implantação autoscale.
Armazenar a base no filesystem desse servidor perderia alterações ou
exigiria republicar o backend. Portanto o **GitHub é a fonte durável**.
`GET /api/threat-catalog` é apenas um espelho somente leitura, com cache
de 15 minutos e HTTP 503 quando ainda não há uma base validada. Na falha,
um espelho já carregado serve a versão anterior (`X-Catalog-Stale: true`).
O Android usa o GitHub diretamente, sem depender da implantação do espelho.

HTTPS continua com validação padrão de certificado/hostname, URL fixa e sem
redirects. Além disso, envelopes **formato 2** exigem assinatura **RSA-3072,
SHA-256, PKCS#1 v1.5** (`SHA256withRSA` no Android), com domínio exclusivo:
`maguardian/threat-catalog/v2\n<keyId>\n<sha256>\n<payload UTF-8 exato>`.
SHA-256 continua verificando integridade; a assinatura prova a autorização.
`keyId` é SHA-256 do SPKI DER da chave pública. A assinatura é base64 canônico,
384 bytes. Não se reserializa o payload entre assinatura e verificação.

As chaves públicas e limites de versão são **fixos no APK**; o download não pode
adicionar chaves, revogá-las nem alterar essa política. Campos inesperados,
envelopes sem assinatura, versão fora dos limites, chave desconhecida/revogada,
assinatura inválida ou SHA recalculado por invasor são rejeitados **antes**
de substituir o cache. O espelho web usa os mesmos pinos públicos gerados,
não o arquivo remoto como fonte de confiança.

A chave privada pertence ao operador e fica exclusivamente no Secret
`THREAT_CATALOG_SIGNING_KEY`, ou em ambiente de assinatura isolado equivalente.
Não é chave de assinatura do APK/AAB e nunca é incluída no Git, APK, assets,
logs, argumentos de CLI ou resposta HTTP. Não reutilizar a chave da Play Store.
Não colocá-la em workflows controlados por quem pode escrever no repositório.
Não executar scripts alterados por um invasor no ambiente que contém a chave.

**Limites:** isso protege APKs já instalados contra alteração do catálogo no
repositório. Não evita indisponibilidade, replay de uma versão ainda aceitável
após limpar dados, nem protege a compilação de fontes adulterados pelo invasor.
O operador deve revisar mudanças de código e pinos antes de assinar um novo
AAB. A conta Google Play e sua chave de upload devem continuar protegidas.

## Provisionamento inicial (operador)

**Já provisionado neste conjunto:** catálogo v3, pinos públicos Android/web e
bootstrap offline assinados pela chave do operador. Para um `git pull` ou build,
não gere outra chave nem repita `--initial`. Os passos abaixo documentam a
configuração inicial e não são necessários em cada atualização.

1. No workspace, `cd android-native` e `npm run catalog:key-create`.
   No clone Android, executar o mesmo comando na raiz após `npm install`.
   Essa ferramenta só é executada pelo operador: cria RSA-3072 PKCS#8 PEM em
   `$HOME/.maguardian-signing/catalog-rsa3072.pem`, fora do Git, com permissão
   0600, sem imprimir o material privado nem substituir arquivo existente.
   Guardar backup cifrado fora do repositório e controlar acesso a ele.
2. Inserir o conteúdo desse arquivo **somente no fluxo seguro de Secrets**,
   com o nome `THREAT_CATALOG_SIGNING_KEY`. Nunca colar em chat ou commit.
3. Revisar a base existente e incrementar `reviewed.json` para versão > 2,
   com data UTC atual. A versão 2 era o formato legado sem assinatura.
4. `npm run catalog:provision -- --initial` gera somente artefatos públicos:
   `trusted-keys.json`, `shared/catalog-trust.ts`, `ThreatCatalogKeys.kt` e
   `assets/threat-catalog-bootstrap.json`. O bootstrap assinado preserva a
   base adicional revisada mesmo no primeiro início offline.
5. `npm run catalog:check-trust` verifica os pinos Android/web e o bootstrap,
   **sem acessar segredo**. Testar antes de compilar. O provisionamento não
   altera versionCode, não gera AAB e não publica na Play Store.
6. Para substituir uma única vez o arquivo legado local, executar
   `npm run catalog:publish -- --migrate-unsigned`. Essa opção não existe no
   Android: o APK nunca aceita o formato legado como atualização.
7. Revisar/compilar/assinar o AAB e publicar manualmente. APKs antigos mantêm
   sua base embarcada/cache, mas não aceitam formato 2. A instalação do novo
   APK é necessária para fixar a chave; não há ativação retroativa silenciosa.

O provisionamento não deve ser considerado concluído enquanto a chave do
operador não estiver configurada e `catalog:check-trust` não passar. Uma lista
de pinos vazia falha fechada: nunca é substituída por confiança em TLS somente.

## Rotação e revogação

- **Rotação planejada:** criar outra chave fora do Git, configurá-la no ambiente
  seguro e usar `npm run catalog:provision -- --public` para obter somente SPKI
  e ID públicos. Adicionar a sucessora ao `trusted-keys.json`, com intervalo
  de versões explícito, mantendo a antiga ativa durante a transição. Regenerar
  pinos/bootstrap com `npm run catalog:provision`, verificar e publicar um novo
  APK manualmente. Só depois assinar catálogos com a sucessora. APKs que ainda
  não a têm rejeitam sua assinatura e conservam a última base válida.
- **Revogação:** marcar a chave comprometida `revoked` na política local,
  assinar o bootstrap revisado com uma chave não comprometida e regenerar os
  pinos. Distribuir um APK de recuperação manualmente. Cache assinado pela
  chave revogada não é reutilizado: usa-se o bootstrap assinado válido ou a
  base original embarcada; as preferências “Manter” não são apagadas.
- **Catálogo remoto adulterado/revogado:** o publicador recusa sobrescrever
  saída inválida. Recuperar um catálogo assinado válido de backup revisado, ou
  gerar uma saída nova em caminho explícito, revisá-la e substituir o arquivo
  remoto deliberadamente. Não confiar na versão anunciada pelo invasor para
  definir o piso de recuperação; usar a última versão conhecida do operador.
- APKs offline/antigos não recebem revogações de chave sem uma atualização de
  APK. O repositório jamais controla o trust store do app. Não há promessa de
  revogação instantânea ou bloqueio retroativo de chaves num APK antigo.

## Publicar indicadores (sem novo APK)

1. Revisar a pesquisa original e a licença. Registrar pacote, hashes SHA-256
   completos, descrição própria, fonte, atribuição e evidência da revisão.
2. Editar `reviewed.json`: incrementar `version` (inteiro, até 2147483647),
   atualizar `publishedAt` e `review.reviewedAt` em UTC (`...Z` ou `...SSSZ`).
   Para corrigir/remover entradas adicionais, publicar uma **versão maior**
   com a lista completa desejada. A base embarcada é independente.
3. Com a chave privada já configurada no ambiente seguro, no workspace:
   `cd android-native && npm run catalog:publish`.
   No repositório Android obtido com `git pull`, instalar as dependências de
   ferramentas com `npm install` e executar `npm run catalog:publish` na raiz.
   O diretório `android-native/` contém todos os arquivos necessários e mapeia
   integralmente à raiz do repositório: `tools/`, `shared/`, `threat-catalog/`
   e `package.json`, além de `app/`.
   O comando rejeita candidatos, ausência de fonte/licença/revisão, versão
   reutilizada/antiga, campos inesperados, pacotes duplicados, hashes inválidos,
   datas futuras e catálogos acima de 2 MiB.
4. Revisar o diff e enviar **somente os metadados** ao branch `main`, preservando
   mudanças remotas. É importante não reformatar o `payload` depois de gerar
   seu hash. O comando gera o arquivo, mas **não faz push**. Publicar o envelope
   inteiro numa única atualização do arquivo:

   ```sh
   git add threat-catalog/reviewed.json threat-catalog/catalog.json
   git commit -m "Atualizar indicadores revisados (sem novo APK)"
   git push origin main
   ```
5. No Android, tocar **Atualizar base de ameaças** ou aguardar a tarefa diária.
   O app também verifica ao iniciar, com intervalo mínimo automático de 6 horas.
   Atualizar a base não desinstala apps nem inicia scan/remediação; a próxima
   verificação usa os novos indicadores.

O comando pode gerar um arquivo novo se não existir saída, mas recusa
sobrescrever saída inválida. Uma saída `.tmp` remanescente deve ser inspecionada
e removida pelo operador antes de nova tentativa.

## Recuperação e offline

O Android valida limite, campos/tipos, assinatura, política da chave, schema,
datas, hash do payload e versão
antes da escrita. `AtomicFile` preserva a versão anterior em falhas de escrita;
o snapshot só muda depois da gravação. Cache na pasta **noBackupFilesDir**:
não se restaura uma base antiga de backup. Falhas de rede, TLS, redirect,
formato, assinatura ou integridade deixam a última base válida ativa. Ela não
expira offline. Sem cache assinado válido, usa o bootstrap assinado incluído
no APK e a base original embarcada. O cache legado sem assinatura não é
promovido a confiável. Reinstalar/limpar dados remove o histórico de versão,
mas não os pinos e o piso de versões incluídos no APK. Assinatura e checksum
do cache são revalidados ao iniciar; não há alteração em “Manter”.

## Fontes e custos

A base inicial usa somente IOCs de Android GravityRAT publicados pela
ESET, licença BSD-2-Clause, com atribuição integral no catálogo e no Android
em **Fontes e licenças** (disponível offline). São os dois pacotes documentados
e os três hashes da mesma pesquisa; o detector exige a interseção pacote/hash.

Não há scraper, crawler, API paga, VirusTotal nem promoção automática de
candidatos. Coleta futura deve ter allowlist de fontes, permissão/licença de
redistribuição, orçamento, limites de requisições e fila separada de candidatos.
Nunca interpretar contagem de engines de URL da Play Store como análise do APK.
Uma proposta ou feed não verificado deve permanecer fora de `reviewed.json`.

O esquema exige `status: "confirmed"` **e** revisão documental; isso não substitui
a responsabilidade do revisor de checar a evidência original. Os campos de
atribuição devem trazer o aviso completo exigido pela licença da nova fonte,
pois são exibidos offline no app.

## Verificação

### Ensaio descartável de rotação entre APKs

`DISPOSABLE_ANDROID=true ANDROID_SERIAL=emulator-5554 bash tools/validate-android-catalog.sh --rotation`

Requer SDK/Gradle e emulador novo, acelerado. Recusa aparelhos físicos e uma
instalação QA já existente. Usa exclusivamente o pacote
`com.maguardian.security.catalogvalidation`, com assinatura debug e versões
41 → 42 → 43. A versão de produção permanece 40; não gera releases nem publica
APKs. O ensaio não repete os testes genéricos de reinício/Android antigo.

- Gera duas chaves RSA-3072 temporárias em `app/build/catalogRotationSession/`
  (ignorado, PEM 0600), sem ler Secrets, e fixa políticas **imutáveis** em cada
  APK QA. Nenhuma chave é injetada no processo do teste.
- APK antigo: bootstrap A v10, cache A v11; B v12 é rejeitado como desconhecido,
  sem alterar bytes do cache. Nova execução em outro processo revalida A v11.
- APK de transição: atualização real com `adb install -r`, sem limpar dados;
  aceita A/B e instala B v12. Outro processo confirma o cache e prepara A v13,
  ainda autorizado, para ensaiar a revogação.
- APK de recuperação: atualização real, A revogada/B ativa; descarta A v13 como
  fonte confiável e usa bootstrap B v14 **offline**. Rejeita atualização A v15;
  aceita B v16 e a revalida em outro processo.
- Em todas as fases, confere SHA-256 exato de um APK instrumentado inofensivo,
  proteção embarcada e persistência/efeito de **Manter**. Marcadores privados
  confirmam que os dados sobreviveram aos upgrades.

Somente logs de build/teste, versões e checksums públicos vão para o diretório
de evidências (`ANDROID_VALIDATION_OUTPUT`); APKs e chaves nunca são enviados.
Ao terminar, descarte o emulador e `app/build/catalogRotationSession/`. Não use
esse trust store QA para assinar um AAB de produção.

`node --test tools/catalog-rotation-fixtures.test.mjs` verifica isolamento,
assinaturas, bootstraps e políticas das fixtures sem SDK. Esse teste sozinho
**não** substitui a instalação sucessiva dos APKs no Android.

**Resultado em 10/10/2026:** aprovado em emulador descartável Android 36
(Google APIs, x86_64, aceleração KVM), com seis invocações instrumentadas
aprovadas e upgrades reais 41 → 42 → 43. Confirmados cache, bootstrap de
recuperação, rejeições por chave desconhecida/revogada, detecção por hash exato,
base embarcada e **Manter**. Pinos/bootstrap de produção permaneceram intactos.
[Execução e evidências públicas](https://github.com/Anderraupp/MAGuardian-Android/actions/runs/38055692298).
O teste aguarda a gravação assíncrona de “Manter” antes do encerramento forçado
do processo; não regrava a preferência nas fases de verificação.

- Na raiz do repositório Android: `npm run catalog:test`.
- Na raiz do workspace: `npx tsx --test shared/threat-catalog.test.ts server/threat-catalog.test.ts`.
- No workspace: `npm run check`.
- No workspace: `bash script/test-android-catalog.sh` — testes JVM do parser com
  dependências baixadas para `generated-artifacts/`, sem APKs de malware.
- Compilação Android completa/emulador: requer Android SDK/Gradle e não é
  substituída pelo teste JVM.
- Testes instrumentados de armazenamento alteram catálogo/preferências da
  instalação-alvo; executar somente numa instalação descartável/emulador.
