# Validação do catálogo em Android descartável

Não executar os testes no aparelho de uso. Eles não usam o pacote normal:
o build `catalogValidation` tem o identificador
`com.maguardian.security.catalogvalidation`, mantém a versão do aplicativo e usa
assinatura de debug. Não é uma release.

## Pré-requisitos

- JDK 17 e Android SDK (`ANDROID_HOME`), plataforma Android 36 e Build Tools 35.
- Emulador Android 36 recém-criado, sem dados pessoais, com internet.
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
- A Application de testes não inicia downloads, analytics ou faturamento.
- Os testes de armazenamento usam diretórios e preferências aleatórios, com
  limpeza ao final e reinicialização da memória somente no código de testes.
- Catálogo inválido, regressão de versão e reutilização de versão são rejeitados.
- Correspondência exata lê o APK já instalado, localmente; nenhum APK é transferido.
- Versões maiores podem alterar e remover indicadores.
- Falha ao abrir a escrita, escrita parcial abortada, cache corrompido e, quando
  disponível, `/dev/full` para simular erro de escrita.
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
