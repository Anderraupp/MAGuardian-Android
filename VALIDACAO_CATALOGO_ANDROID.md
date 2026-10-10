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
