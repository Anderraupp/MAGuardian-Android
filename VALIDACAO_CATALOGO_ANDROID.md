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

## Resultado confirmado em 10/10/2026

[Execução aprovada no GitHub](https://github.com/Anderraupp/MAGuardian-Android/actions/runs/38024274338)
no commit `1e7fa9090fc83022eccbe9a3ecbea8d69f3c0edc`.

- Compilação completa com JDK 17, Gradle 8.11.1, SDK Android 36 e Build Tools 35:
  `assembleDebug`, `assembleCatalogValidation` e
  `assembleCatalogValidationAndroidTest` concluídos com sucesso.
- Os seis testes Node de formato/publicação já haviam passado; seus fontes não
  foram alterados pelos ajustes da suíte Android.
- Sintaxe do roteiro shell e consistência do diff verificadas.

| Fase Android | Resultado |
| --- | --- |
| Online | `OK (12 tests)`: 11 aprovados e o caso exclusivamente offline adiado para a fase seguinte |
| Preparação do reinício | `OK (1 test)` |
| Sem Wi-Fi/dados | `OK (2 tests)`, incluindo o caso offline adiado e a interface/licenças |
| Após reinício real, ainda sem rede | `OK (1 test)` |

Não houve falha instrumentada na execução aprovada. A falha de sincronização
verificou que o snapshot anterior e o arquivo anterior continuaram disponíveis.
O job executou o atualizador real; o pedido de periodicidade diária e sua
persistência também foram verificados. Depois do reboot, o catálogo, a
correspondência local do APK, a escolha Manter e o job persistido foram preservados.

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
A branch principal permaneceu no commit
`716a630ca78f483f00bf391b9c51ecc65aa58d98`.
Não foi criada release nem alterada a versão: código 40, nome 1.6.2.
