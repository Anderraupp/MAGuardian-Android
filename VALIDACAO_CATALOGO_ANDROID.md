# Validação do catálogo em Android descartável

Não executar os testes no aparelho de uso. Eles não usam o pacote normal:
o build `catalogValidation` tem o identificador
`com.maguardian.security.catalogvalidation`, mantém a versão do aplicativo e usa
assinatura de debug. Não é uma release.

## Pré-requisitos

- JDK 17 e Android SDK (`ANDROID_HOME`), plataforma Android 36 e Build Tools 35.
- Emulador Android 11 (API 30) ou mais recente, recém-criado, sem dados pessoais,
  com internet. A compilação continua usando a plataforma Android 36.
- Preferencialmente aceleração KVM; execução sem aceleração é muito mais lenta.
- Repositório Android completo, incluindo o AAR local já presente em `app/libs`.

## Executar

Inicialize um AVD descartável com `-wipe-data -no-snapshot`. Nunca reutilize um
AVD que tenha dados importantes. Então, na raiz do repositório Android:

```sh
export ANDROID_HOME=/caminho/para/android-sdk
export ANDROID_SERIAL=emulator-5554
export DISPOSABLE_ANDROID=true
bash tools/validate-android-catalog.sh
```

O comando compila somente debug/validação, instala e limpa **somente o pacote de
validação**, executa os cenários online, desliga Wi-Fi/dados, testa a proteção
offline, reinicia o emulador de verdade e confirma o catálogo, a escolha Manter e
o JobScheduler persistido. Ao sair, restaura Wi-Fi/dados. O pacote normal
`com.maguardian.security` nunca é instalado, apagado ou desinstalado pelo comando.
Descarte o AVD depois.

Evidências ficam em `../generated-artifacts/android-validation` por padrão.
Use `ANDROID_VALIDATION_OUTPUT` para outro diretório; não envie SDK, imagens,
APKs ou logs de emuladores para o Git. Uma execução falha se qualquer fase
instrumentada não tiver resultado `OK`.

## Isolamento e cenários

- O runner recusa o pacote normal. Cada teste exige `-e disposable true`.
- O APK instrumentado usa `com.catalogvalidation.fixture`, fora dos prefixos
  confiáveis do aplicativo. O alvo continua sendo apenas o pacote de validação.
- A Application de testes não inicia downloads, analytics ou faturamento.
- Os testes de armazenamento usam diretórios e preferências aleatórios, com
  limpeza ao final e reinicialização da memória somente no código de testes.
- Catálogo inválido, regressão de versão e reutilização de versão são rejeitados.
- Correspondência exata lê o APK já instalado, localmente; nenhum APK é transferido.
- Versões maiores podem alterar e remover indicadores.
- Falha ao abrir a escrita, escrita parcial abortada e cache corrompido.
  `/dev/full` simula erro de escrita quando acessível; em emuladores com SELinux
  restritivo, `/dev/null` produz uma falha real de sincronização e verifica o
  rollback do catálogo, sem pular esse teste.
- Handler de instalação e varredura inicial do serviço respeitam o hash e Manter.
- Listas confiáveis e isenção de varredura continuam protegidas.
- Download manual real, cooldown automático, JobScheduler diário persistido,
  execução forçada do job e botão/status/licenças da Activity real.
- Sem rede, falha de atualização preserva o catálogo válido e a base embarcada.
- Reinício real seguido de leitura offline do catálogo persistido.

Forçar o JobScheduler confirma a execução do serviço sem aguardar 24 horas.
O Android pode adiar jobs periódicos por bateria/Doze; o intervalo solicitado é
diário, não uma promessa de execução em horário exato.

O teste do handler de instalação chama sua implementação no processo
instrumentado; ele não pretende simular um aplicativo malicioso real. A
varredura inicial usa o PackageManager real e um APK de testes inofensivo.
Nenhuma opção paga ou assinatura é ativada pelos testes.

## Executar pelo GitHub sem alterar a branch principal

O workflow `.github/workflows/validate-catalog.yml` roda na branch isolada
`agent/catalog-validation`, ou por execução manual quando estiver disponível na
branch selecionada. Usa Ubuntu 24.04, KVM, JDK 17 e um emulador Android 36 recém-criado.

O token da execução tem somente leitura de conteúdo. O workflow não faz commit,
não mescla branches, não publica catálogo e não publica APK ou release. As
evidências são guardadas por sete dias. Uma atualização da branch de validação
cancela somente as execuções antigas desse mesmo grupo de testes descartáveis.

Uma integração posterior na branch principal exige decisão separada. O código
do APK normal não precisa do token temporário usado para preparar a execução.

### Catálogo assinado e isolamento das chaves de QA

O formato atual exige RSA-3072. O roteiro inicia um servidor HTTPS real somente
no runner (`10.0.2.2:8443` visto pelo emulador), com certificado e chave de QA
gerados nessa instalação descartável. Não há conexão falsa nem desativação de
verificação TLS/hostname.

Somente `catalogValidation` usa esse endpoint e certificado. A compilação desse
tipo substitui o provedor de âncoras por um provedor gerado de QA. `debug` e
`release` continuam compilando `ThreatCatalogKeys.kt` original, a política do
operador, e a URL original do GitHub. O provedor de produção não recebe setter,
reset por reflexão ou chave de testes.

As fixtures de alterações de indicadores recebem assinaturas RSA reais de uma
chave criada apenas no emulador. Essa chave fica no armazenamento privado do
pacote descartável para permitir a verificação após reboot. A chave do servidor
fica em `app/build`, fora das evidências enviadas. Nenhuma chave privada de
produção é usada e nenhuma chave privada é embutida nos APKs.

Node e OpenSSL são necessários para preparar essa variante de testes. O build
normal não executa essa preparação. A instalação de QA usa Application simples,
remove o inicializador automático do Facebook e desativa autolog/ID de
anunciante. Esses ajustes não alteram o manifesto nem os SDKs da instalação normal.

## Resultado confirmado em 10/10/2026

### Rota alternativa: cache mais novo ainda válido

Em um emulador **novo e descartável**, execute:

```sh
DISPOSABLE_ANDROID=true ANDROID_SERIAL=emulator-5554 bash tools/validate-android-catalog.sh --rotation-valid-cache
```

Essa rota instala os APKs QA 41 → 42 → 43 com `install -r`, sem limpar dados.
Na transição, grava o catálogo 17 assinado pela sucessora; na recuperação,
revoga a chave antiga e conserva o cache 17, superior ao bootstrap 14.
Não prepara cache revogado nem instala outra atualização durante a recuperação.
Verifica bytes, versão/hash, correspondência exata do APK inofensivo, proteção
embutida e “Manter”, repetindo a verificação após encerrar e reabrir o processo.
Também rejeita a tentativa de substituir esse cache pelo bootstrap mais antigo.

O workflow de rotação oferece `valid-cache` na seleção manual e na branch
descartável `qa/catalog-valid-cache-recovery`. Não usa Secrets, não publica
APKs e mantém a versão de produção. Os logs `rotation-*.log` registram rota,
fase, versão/hash e as verificações de detecção/Manter.

Os testes Node verificam assinaturas/políticas e o contrato dos comandos do
roteiro com ADB simulado; **não substituem a execução instrumentada Android**.
Esta rota nova ainda precisa dessa execução em emulador acelerado; os resultados
históricos abaixo não são evidência de aprovação dela.

[Execução aprovada com o formato assinado](https://github.com/Anderraupp/MAGuardian-Android/actions/runs/38026326505)
no commit `c2a37f4a73e716646fd522db43b75e7b5bed0a3b`.

A execução anterior `38024274338` aprovou o formato anterior. Quando a integração
do projeto trouxe a exigência de assinaturas independentes, essa proteção foi
preservada e a suíte foi adaptada e reexecutada; a aprovação anterior não foi
usada como prova do novo formato.

- Compilação completa com JDK 17, Gradle 8.11.1, SDK Android 36 e Build Tools 35:
  `assembleDebug`, `assembleCatalogValidation` e
  `assembleCatalogValidationAndroidTest` concluídos com sucesso.
- Sintaxe do roteiro shell e consistência do diff verificadas.

| Fase Android | Resultado |
| --- | --- |
| Online | `OK (14 tests)`: 13 aprovados e o caso exclusivamente offline adiado para a fase seguinte |
| Preparação do reinício | `OK (1 test)` |
| Sem Wi-Fi/dados | `OK (2 tests)`, incluindo o caso offline adiado e a interface/licenças |
| Após reinício real, ainda sem rede | `OK (1 test)` |

Não houve falha instrumentada na execução aprovada. A falha de sincronização
verificou que o snapshot anterior e o arquivo anterior continuaram disponíveis.
O job executou o atualizador real; o pedido de periodicidade diária e sua
persistência também foram verificados. Depois do reboot, o catálogo, a
correspondência local do APK, a escolha Manter e o job persistido foram preservados.
O teste adicional fez GET real do GitHub e confirmou que dados sem a autoridade
de assinatura de QA não substituem uma fixture válida. Assinaturas malformadas,
assinaturas falsas com codificação válida, chave desconhecida e formato legado
também foram rejeitados sem alterar cache ou Manter.

Depois dessa execução, apenas o isolamento de Application/analytics no manifesto
de QA foi reforçado: `processDebugMainManifest` e
`processCatalogValidationMainManifest` passaram, e os manifestos mesclados
confirmaram que as opções normais permanecem intactas. Os cenários de catálogo
assinados aprovados não foram alterados. O bytecode de debug confirmou a URL
original e o provedor de produção, sem âncoras de QA.

Os handlers de instalação e varredura foram chamados no processo instrumentado.
Não foi instalado malware real, ativada assinatura, feito teste de pagamento
ou certificado o comportamento de todos os serviços em todos os modelos de celular.
A entrega do broadcast de boot à instalação normal não é demonstrada pelo teste:
o teste confirma o reinício real e a persistência do catálogo e do JobScheduler.
Economia de bateria/OEMs e versões antigas do Android exigem verificações adicionais.

As tentativas locais anteriores, sem `/dev/kvm`, não foram usadas como aprovação:
o Android 36 apresentou ANRs e o Android 11 sofreu encerramento pelo watchdog.
A aprovação acima veio do emulador acelerado Android 36 no GitHub.

As evidências estão no artefato `android-catalog-validation-evidence` dessa
execução e, localmente, em
`generated-artifacts/android-validation/github-passed`, ignorado pelo Git.
As evidências da revalidação assinada ficam em
`generated-artifacts/android-validation/github-signed-passed`.
A branch principal permaneceu no commit
`716a630ca78f483f00bf391b9c51ecc65aa58d98`.
Não foi criada release nem alterada a versão: código 40, nome 1.6.2.

A validação inicial não provisionou a assinatura de produção. Depois, a pedido
do operador para deixar o clone pronto para `git pull`, ele gerou sua chave fora
do Git e a cadastrou no fluxo de Secrets. O catálogo **v3** foi assinado com essa
chave e os pinos públicos Android/web e bootstrap offline foram provisionados
e conferidos por `catalog:check-trust`. Nenhuma chave de QA virou chave de
produção. Compilação e uso do APK não precisam do Secret de assinatura.

O conjunto de provisionamento é validado na branch descartável antes do envio
atômico à `main`. A subpasta parcial antiga do repositório Android é preservada,
mas o build usa a raiz com `settings.gradle` e `app/`. O ajuste externo do mínimo
de denúncias comunitárias foi preservado, sem alterar as regras do catálogo.

[Revalidação do conjunto provisionado](https://github.com/Anderraupp/MAGuardian-Android/actions/runs/38053279544)
aprovada no commit `159fc3c5c8cf71cf9e9e70eef7110821b5b96bfd`: os 12 testes Node
do repositório Android autossuficiente e o check público de pinos/bootstrap
passaram sem Secret no GitHub; o build normal e a suíte Android 36 passaram
novamente (13 casos online aprovados, 1 exclusivamente offline adiado; 2 offline,
1 preparação e 1 verificação após reboot real). As evidências locais estão em
`generated-artifacts/android-validation/pull-ci-passed`.

Após essa aprovação, o operador autorizou disponibilizar o conjunto na `main`
para `git pull`, incluindo catálogo v3 assinado, pinos e bootstrap públicos,
ferramentas independentes e documentação. O workflow Build APK é somente manual
e debug: o envio não dispara publicação de release, tag ou Play Store.

## Ensaio de atualização interrompida (somente QA)

O modo abaixo exige emulador descartável e pacote QA ainda não instalado:

```bash
DISPOSABLE_ANDROID=true ANDROID_SERIAL=emulator-5554 \
  bash tools/validate-android-catalog.sh --rotation-interrupted
```

O workflow `validate-catalog-rotation.yml` seleciona esse ensaio na branch
isolada `qa/catalog-interrupted-recovery` ou com a opção manual `interrupted`.
Não executa publicação e não utiliza Secrets de assinatura de produção.

As fases são:

1. Instalar o APK QA 41, carregar cache assinado 13 e gravar “Manter”.
2. Reiniciar apenas o processo para confirmar que esse preparo foi persistido.
3. Criar uma sessão PackageInstaller com o APK QA 43 inteiro, sem commit.
4. Executar `interrupted-staged`: o APK 41 deve continuar utilizável, com cache
   13, proteção local, hash de APK detectável e “Manter” respeitado.
5. Abandonar a sessão e executar `interrupted-abandoned` em processo novo.
   Repetir as verificações sem regravar cache, checkpoint ou preferências.
6. Concluir uma instalação nova com `adb install -r`, sem limpar dados nem
   desinstalar. O APK 43 deve recuperar pelo bootstrap 14, aceitar a atualização
   assinada 16 e conservar “Manter”, inclusive após reiniciar seu processo.

Os arquivos `session-*.txt` registram criação, escrita completa e abandono.
`package-*.txt` e `installed-*.sha256` registram as versões e o hash do APK
realmente instalado. O hash deve permanecer igual antes, durante e depois do
abandono; depois do upgrade deve corresponder ao novo APK compilado. Cada log
instrumentado registra o resultado da fase, cujas asserções verificam versão
de APK, catálogo e “Manter”. O cleanup só abandona a sessão criada pelo próprio
ensaio.

Não há reboot do dispositivo, instalação de malware real, alteração de pinos
de produção ou mudança da versão normal **1.6.2 / código 40**. Essas versões
41/43 pertencem exclusivamente aos APKs de QA. O ensaio não é um pré-requisito
adicional para gerar o AAB de produção no Android Studio.

### Resultado confirmado em 10/10/2026

[Execução Android 36 aprovada](https://github.com/Anderraupp/MAGuardian-Android/actions/runs/38057069319),
commit `da41152e0c469088d7eef35edb3f18ba472aefee`, na branch QA isolada.
As seis fases (`old`, `old-restart`, `interrupted-staged`,
`interrupted-abandoned`, `recovery` e `recovery-restart`) retornaram
`OK (1 test)`, sem falhas. A sessão recebeu o APK completo (8.993.248 bytes)
e foi abandonada com sucesso antes de qualquer commit dessa sessão.

| Momento | APK instalado | Catálogo verificado | Manter |
| --- | --- | --- | --- |
| Preparo e novo processo | 41 | 13, chave antiga válida | preservado |
| Sessão com APK 43 escrito, sem commit | 41 | 13 | preservado |
| Sessão abandonada, novo processo | 41 | 13 | preservado |
| Upgrade real sem apagar dados | 43 | bootstrap 14, depois atualização 16 | preservado |
| Novo processo após upgrade | 43 | 16 | preservado |

O hash do APK instalado antes, durante e após o abandono permaneceu
`b33f0fd703622866d924889054fa87a17290d76f1541a68e781308bc2d380628`.
Após a instalação real, passou para
`455efecf5ad92e3a5bd93b2b65b070823e732d9115b7f1871266c010c8bdd90d`,
igual ao hash do APK QA 43 compilado. A conferência de fontes/pinos/versão de
produção do workflow também passou. A `main` do repositório Android não foi
modificada por este ensaio.

Evidências públicas: artefato `android-catalog-rotation-evidence` da execução,
e cópia local ignorada pelo Git em
`generated-artifacts/android-interrupted/passed`. Não contém APKs nem chaves
privadas. Isso demonstra abandono explícito de uma sessão; não simula queda de
energia durante o commit do instalador nem certifica todos os fabricantes.

## Escrita AtomicFile interrompida por encerramento de processo (somente QA)

Este ensaio é separado tanto do rollback com `failWrite` em
`ThreatCatalogStorageTest` quanto do abandono de sessão PackageInstaller.
Exige emulador descartável **API 30+**, com o pacote QA ainda não instalado:

```bash
DISPOSABLE_ANDROID=true ANDROID_SERIAL=emulator-5554 \
  bash tools/validate-android-catalog.sh --atomic-pending
```

O workflow dedicado `validate-catalog-atomic.yml` usa Android 36 acelerado na
branch isolada `qa/catalog-atomic-pending`. Não publica APK nem usa Secrets.

1. Instala apenas o APK QA 41 e o APK instrumentado inofensivo. Persiste cache
   assinado 11 e “Manter”, aguardando a gravação das preferências. Confirma o
   preparo em outro processo antes de começar a interrupção.
2. A fase `atomic-write` usa o `AtomicFile` Android real sobre o cache QA.
   Grava e sincroniza apenas um prefixo JSON no `.new`, mantém o stream aberto
   e emite um checkpoint sincronizado com PID e hashes. Não chama `failWrite`,
   `finishWrite` nem fecha o stream por `use`/`finally`.
3. O controlador aguarda o status de prontidão, confere que o PID pertence
   exatamente a `com.maguardian.security.catalogvalidation`, compara os bytes
   da base/preferências e encerra **somente esse pacote QA**. Confirma que o
   processo acabou e que o `.new` incompleto ainda existe, com os mesmos bytes.
4. `atomic-verify`, em processo distinto, observa o pendente antes de carregar
   o catálogo. O leitor de produção deve descartar o `.new` e carregar o cache
   11 completo, sem substituir seus bytes/hash. Confirma a correspondência por
   hash do APK local inofensivo e a proteção embutida, respeitando “Manter”.
5. `atomic-restart` confirma novamente em outro processo que o temporário não
   reaparece. As duas verificações não regravam preferências; conferem seu hash
   e data de modificação, além da escolha “Manter”.

Evidências ignoradas pelo Git: `base-*.json`, `storage-*.sha256`,
`prefs-*.xml` (somente dados sintéticos QA), `pending-live.new`,
`pending-killed.new`, `pending.sha256`, `atomic-pending-ready.json`,
`atomic-kill.txt`, `atomic-write.log`, `atomic-verify.log`,
`atomic-restart.log`, `files-*.txt` e hashes do APK instalado.
O processo escritor **não deve** retornar `OK`; as verificações de leitura
devem retornar `OK (1 test)` e `ATOMIC_PENDING_OK`.

O teste Node `tools/catalog-atomic-driver.test.mjs` verifica apenas o contrato
de comandos e as guardas com ADB simulado; não substitui o ensaio Android real.
Não há upgrade de APK, corrupção da base principal, reboot do dispositivo,
alteração de produção, pinos ou versão normal. O ensaio não comprova queda de
energia, todos os fabricantes nem a implementação antiga de AtomicFile com
`.bak` em APIs anteriores a 30.

### Resultado confirmado em 10/10/2026

[Execução Android 36 aprovada](https://github.com/Anderraupp/MAGuardian-Android/actions/runs/38062962988),
commit `10a79795f39049b2459c2406c7b7b21dd6e09092`, branch QA isolada
`qa/catalog-atomic-pending`. As fases `old`, `old-restart`, `atomic-verify`
e `atomic-restart` retornaram `OK (1 test)`.

O escritor PID 5028 emitiu `ATOMIC_PENDING_READY` e foi realmente encerrado:
seu log terminou com `shortMsg=Process crashed.`, **sem** resultado `OK`.
O `.new` de **33 bytes** permaneceu idêntico depois do encerramento. Os leitores
novos, PIDs 5096 e 5155, emitiram `ATOMIC_PENDING_OK`, confirmando detecção,
“Manter” e preferências sem alterações. O leitor inicial encontrou o temporário
e o descartou; o segundo confirmou que ele não reapareceu.

| Arquivo | Antes, durante e depois da interrupção |
| --- | --- |
| Base principal, 1.419 bytes | SHA-256 `1839761d107f2ae6fdab90bc0edfcb15820d63ae28bb7d595a3a2a75a6f5da47`, idêntico nos cinco momentos |
| Preferências QA, 452 bytes | SHA-256 `60f4f0055c16fa25f42c9bb2a257c41134efd806a957462168e24a20abfe45f3`, idêntico; data de modificação preservada nas asserções |
| `.new` incompleto, 33 bytes | SHA-256 `e15bba45f6e688f5067970b6b1f9d27fb98f674dc858eff96dec086ddd8f5e48`, idêntico vivo/encerrado; ausente após leitura |

O APK QA permaneceu no código **41**, com hash instalado
`9b00f05c0cffcbd3f3b8d3105ae10bf0d4b6055a9ccf78b37609aa38e68009ec`
igual ao compilado e aos dois processos de verificação. A checagem de fontes,
pinos e versão normal também passou. A `main` Android não foi modificada.
Evidências baixadas e inspecionadas em
`generated-artifacts/android-atomic/evidence`, ignoradas pelo Git.

Localmente, os três testes Node de fixtures/contrato passaram. Após a execução
Android, o controlador recebeu uma guarda explícita que recusa um escritor
com resultado JUnit `OK`; o teste simulado confirmou também essa recusa. Isso
não mudou o ensaio instrumentado aprovado nem foi tratado como nova prova Android.

## Recuperação direta com cache antigo danificado (somente QA)

Em emulador descartável novo, usar **um** dos modos:

```bash
DISPOSABLE_ANDROID=true ANDROID_SERIAL=emulator-5554 \
  bash tools/validate-android-catalog.sh --rotation-corrupt-truncated
# Ou, em outro emulador novo:
DISPOSABLE_ANDROID=true ANDROID_SERIAL=emulator-5554 \
  bash tools/validate-android-catalog.sh --rotation-corrupt-tampered
```

A branch isolada `qa/catalog-corrupt-cache`, ou a opção manual `corrupt`,
executa os dois modos em jobs independentes do workflow de rotação. Os demais
modos continuam usando um único job. O teste não inclui a versão de transição,
reboot do dispositivo ou cache mais novo válido assinado pela chave sucessora.

Primeiro, o APK QA 41 instala a base válida 13, grava “Manter” e confirma essa
persistência em outro processo. Depois, a fase `corrupt-prepare` verifica que
a fixture defeituosa é rejeitada **antes da revogação da chave antiga**:

- **Truncado:** corta o JSON assinado e exige falha de parsing.
- **Adulterado:** remove os indicadores, recalcula o checksum SHA-256 e mantém
  a assinatura original; exige rejeição por assinatura inválida. A fixture
  continua sendo JSON válido, com checksum correto.

Somente então o teste escreve a fixture no cache de QA, confirma os bytes e
sincroniza o arquivo. Não usa o instalador de catálogo para aceitar esse conteúdo
e não muda “Manter”. A proteção em memória ainda é a última base válida.

A atualização usa `adb install -r` para o APK QA 43, sem desinstalar ou limpar
dados. A fase `recovery` deve encontrar os mesmos bytes danificados e o
checkpoint anterior, ignorar o cache, carregar o bootstrap assinado 14, manter
a detecção local por hash e a escolha “Manter” e aceitar uma atualização válida
16. Um processo novo confirma a base 16 e “Manter” sem regravar o preparo.

Os logs `corrupt-prepare.log`, `rotation-recovery.log` e
`rotation-recovery-restart.log` demonstram as asserções. `cache-corrupt-prepare.json`
e seu SHA-256 registram o dano efetivamente persistido; os arquivos
`package-*.txt` e `installed-*.sha256` registram os APKs 41/43 instalados e
confirmam que danificar o cache não mudou o APK antigo. Os artefatos separados
são `android-catalog-corrupt-truncated-evidence` e
`android-catalog-corrupt-tampered-evidence`. Não contêm APKs ou chaves privadas.

Não são usados Secrets de assinatura de produção nem alterados pinos, fontes
normais, versão **1.6.2 / código 40**, política de privacidade ou publicação.

### Resultado confirmado em 10/10/2026

[Execução Android 36 aprovada nos dois jobs](https://github.com/Anderraupp/MAGuardian-Android/actions/runs/38057748139),
commit `414ea14a30acd05fe17af4532f598b466d502f7c`, branch QA isolada.
Cada caso executou cinco fases aprovadas, todas com `OK (1 test)`:
`old`, `old-restart`, `corrupt-prepare`, `recovery` e `recovery-restart`.

| Cache persistido antes do upgrade | Rejeição no APK antigo | Após upgrade direto 41 → 43 |
| --- | --- | --- |
| JSON truncado, 709 bytes | JSONException | bootstrap 14, depois atualização 16; detecção e Manter preservados |
| JSON adulterado, 798 bytes, indicadores removidos e checksum correto | assinatura inválida, ainda sob a chave antiga ativa | bootstrap 14, depois atualização 16; detecção e Manter preservados |

Os artefatos baixados confirmaram que o primeiro JSON não pode ser lido e que
o segundo contém indicadores vazios e checksum SHA-256 correto. As asserções
nativas rejeitaram sua assinatura antes de persistir a fixture danificada.
Depois do upgrade, os testes conferiram os mesmos bytes no cache, a origem
assinada do bootstrap, a detecção exata do APK inofensivo e a preferência
“Manter” já gravada, sem regravar o preparo.

Nos dois casos, o hash do APK instalado permaneceu igual durante a corrupção
do arquivo de cache. O hash instalado depois do upgrade correspondeu ao APK QA
43 compilado. O workflow também confirmou que as fontes, pinos e versão de
produção não foram modificados. A branch principal Android não foi alterada.

Evidências locais, ignoradas pelo Git:
`generated-artifacts/android-corrupt/truncated/passed` e
`generated-artifacts/android-corrupt/tampered/passed`. O teste não comprova
queda de energia durante escrita, fabricantes diferentes ou todos os formatos
possíveis de corrupção; comprova os dois danos persistidos descritos acima.

## Cache persistido acima do limite durante upgrade direto

Ensaio opcional, sem bloquear `git pull` ou publicação:

```bash
DISPOSABLE_ANDROID=true ANDROID_SERIAL=emulator-5554 \
  bash tools/validate-android-catalog.sh --rotation-oversized
```

O workflow separado `.github/workflows/validate-catalog-oversized.yml` usa
Android 36 acelerado, em instalação descartável, na branch
`qa/catalog-oversized-recovery`. Não executa os outros ensaios de upgrade,
Android antigo ou reboot, e não publica APKs.

1. Instala o APK QA antigo 41, aceita o cache assinado 13, grava “Manter” e
   confirma a persistência em outro processo.
2. Ainda no APK antigo, grava **8.388.608 bytes (8 MiB)** no cache real em
   `no_backup`. O conteúdo é o envelope válido anterior seguido de espaços
   JSON: não altera assinatura, payload ou checksum. A gravação é feita em
   blocos de 8 KiB, sincronizada em disco. A fixture contém somente a receita
   do tamanho, não o arquivo grande.
3. Faz upgrade direto ao APK QA de recuperação 43, sem `pm clear`, uninstall
   ou APK de transição. Confere hashes dos APKs realmente instalados.
   Encerra o pacote QA logo após a instalação para não reutilizar snapshots
   de receivers/serviços iniciados automaticamente por `MY_PACKAGE_REPLACED`.
4. Em processo novo, sem reset por reflexão, executa o inicializador de
   produção sobre o cache persistido. Exige bootstrap 14 assinado pelo
   sucessor, correspondência com o hash do APK inofensivo, proteção embutida
   e “Manter”. O ensaio falha se a inicialização demorar 10 segundos ou mais.
5. Observa separadamente o leitor de produção sobre o mesmo arquivo:
   deve lançar `Catálogo muito grande`, consumir mais de `MAX_BYTES`
   (2.097.152 bytes), mas no máximo um bloco adicional de 8 KiB, e parar
   antes de ler o arquivo completo. Isso não muda o limite de produção.
6. Repete a recuperação em outro processo, sem substituir o cache por uma
   atualização menor. Nas duas verificações, os hashes do cache e das
   preferências, e a data de modificação destas, permanecem iguais. Nenhuma
   verificação regrava “Manter”.

As fases `oversized-prepare`, `recovery` e `recovery-restart` usam o canal de
status da instrumentação para registrar tamanho/hash, bytes lidos, tempo de
inicialização e resultados. As verificações precisam retornar `OK (1 test)`
e `OVERSIZED_OK`. Evidências incluem `oversized-evidence.json`, logs,
versões e hashes de APK; **não incluem o conteúdo grande, APKs ou chaves**.
As chaves RSA-3072 são efêmeras e ficam em diretórios de build ignorados.

`tools/catalog-oversized-driver.test.mjs` verifica apenas o contrato do
controlador com ADB simulado; não substitui o ensaio Android real.
O resultado não mede o pico de heap, todos os fabricantes ou falta de espaço
para instalação; observa rejeição limitada, inicialização e proteção no
emulador usado. Não altera fontes de produção, pinos, permissões ou versão normal.

### Resultado confirmado em 10/10/2026

[Execução Android 36 aprovada](https://github.com/Anderraupp/MAGuardian-Android/actions/runs/38064065302),
commit `83fd6b7`, na branch QA isolada `qa/catalog-oversized-recovery`.
As cinco fases (`old`, `old-restart`, `oversized-prepare`, `recovery`,
`recovery-restart`) retornaram `OK (1 test)`.

- Cache persistido: **8.388.608 bytes**, SHA-256
  `911f02d2c609e3009b57d86ae9eb29ca501d6ada56f293e0889f17dcfcd19481`.
- Leitor limitado: **2.105.344 bytes** nas duas verificações, exatamente
  `MAX_BYTES + 8192`, sem consumir os 8 MiB.
- Inicialização fria: **21 ms** após o upgrade e **23 ms** no processo seguinte.
- Bootstrap assinado **14**, detecção local e proteção embutida aprovadas.
  “Manter” continuou respeitada; hash e data das preferências não mudaram.
- APK antigo realmente instalado: QA **41**, SHA-256
  `f66bafb494a298ce3209215c2d026bb82d6d4397d783c40e495cdef23ade0755`.
- APK de recuperação instalado: QA **43**, SHA-256
  `6902dbe7e2a9ba1e7b12bac5ff195f0e7440291293b30572bbda01e2b0e6615c`.

Evidências locais ignoradas:
`generated-artifacts/android-oversized/passed/`.
O workflow confirmou fontes/pinos/permissões/versão normal intactos e apagou
as chaves efêmeras. Não houve publicação de APK nem alteração da branch `main`.

A primeira execução detectou que `MY_PACKAGE_REPLACED` já havia carregado o
bootstrap antes da medição. O controlador foi corrigido para encerrar o
processo automático após instalar, preservando os dados, antes da leitura
fria. A execução aprovada acima inclui essa correção; não houve alteração do
inicializador de produção nem reset artificial do snapshot.

## Processo encerrado depois do commit atômico

Ensaio opcional, separado do `.new` incompleto:

```bash
DISPOSABLE_ANDROID=true ANDROID_SERIAL=emulator-5554 \
  bash tools/validate-android-catalog.sh --atomic-committed
```

O workflow `validate-catalog-atomic.yml` seleciona `committed` na branch
isolada `qa/catalog-atomic-committed` (ou pelo campo `boundary` no disparo
manual). Usa emulador descartável e chaves RSA-3072 efêmeras. Não publica
APKs, reinicia o dispositivo, faz upgrade, limpa dados ou altera produção.

- `old` e `old-restart` persistem e confirmam o cache assinado 11 e “Manter”.
- `committed-write` valida a fixture assinada 13 sob a mesma chave QA,
  grava todos os bytes com `AtomicFile.finishWrite` e confirma disco 13
  enquanto o snapshot em memória continua em 11. Não chama `install`
  nem publica o novo snapshot. Após registrar evidência sincronizada e
  `ATOMIC_COMMITTED_READY`, aguarda o controlador encerrar somente o pacote
  `com.maguardian.security.catalogvalidation`, cujo PID é conferido.
- `committed-verify` e `committed-restart` inicializam em processos novos,
  sem reset por reflexão. Exigem snapshot 13 válido, bytes e SHA-256 do
  arquivo iguais ao commit, hash do payload igual ao snapshot e ausência
  de `.new`/`.bak`. Exigem detecção por hash do APK inofensivo, proteção
  embutida e “Manter”, sem regravar cache ou preferências.
- O controlador compara bytes do cache e das preferências antes/depois
  do encerramento e das leituras. A instrumentação também compara a data
  de modificação das preferências. Um escritor que retorna `OK` em vez de
  ser encerrado, ou um leitor sem `ATOMIC_COMMITTED_OK`, reprova o ensaio.

Evidências: `atomic-committed-ready.json`, `atomic-write.log`,
`atomic-kill.txt`, logs das duas leituras, bytes/hashes do cache e
preferências, versão e hash do APK QA instalado. O artefato não contém APKs
ou chaves privadas. `tools/catalog-atomic-driver.test.mjs` verifica somente
o contrato de comandos com ADB simulado; não substitui a execução Android.
O ensaio não comprova queda de energia nem executa o caso `.new` pendente.

### Resultado confirmado em 10/10/2026

[Execução Android 36 aprovada](https://github.com/Anderraupp/MAGuardian-Android/actions/runs/38064837680)
na branch QA `qa/catalog-atomic-committed`. As quatro verificações
(`old`, `old-restart`, `committed-verify`, `committed-restart`) retornaram
`OK (1 test)`. O escritor PID **5245** registrou memória **11** e disco **13**
antes do encerramento; não retornou sucesso JUnit. Os leitores PIDs **5311**
e **5370** carregaram o catálogo **13**, com SHA-256 do arquivo
`9229c2ed7c62e54b6311529ce781debffe56953ed4456e18f0046757eb2de456`
e hash do snapshot
`5c014ee5d767d79f6189e1320522053d2bfb656b52d7dc649c319f7b5f75e779`.
Detecção local, proteção embutida e “Manter” passaram; bytes e data das
preferências permaneceram iguais. O hash do APK instalado permaneceu igual
ao QA antigo **41**, sem upgrade. O workflow confirmou produção intacta e
apagou o material privado efêmero. Evidências locais ignoradas:
`generated-artifacts/android-committed/passed/`. A branch `main` não foi alterada.
