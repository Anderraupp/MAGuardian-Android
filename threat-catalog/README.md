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

Autenticidade da origem: HTTPS com validação padrão de certificado e hostname,
URL fixa, sem redirects nem configuração de URL remota. A confiança inclui
GitHub e os mantenedores com acesso de escrita ao repositório. **Não há
assinatura digital independente**: SHA-256 verifica integridade, não prova
autoria. Uma invasão da conta/repositório pode publicar indicadores indevidos.
Proteger as contas e revisar mudanças é parte dessa fronteira de confiança.
O catálogo não carrega código e não altera a própria origem ou regras.

## Publicar indicadores (sem novo APK)

1. Revisar a pesquisa original e a licença. Registrar pacote, hashes SHA-256
   completos, descrição própria, fonte, atribuição e evidência da revisão.
2. Editar `reviewed.json`: incrementar `version` (inteiro, até 2147483647),
   atualizar `publishedAt` e `review.reviewedAt` em UTC (`...Z` ou `...SSSZ`).
   Para corrigir/remover entradas adicionais, publicar uma **versão maior**
   com a lista completa desejada. A base embarcada é independente.
3. No workspace: `cd android-native && npm run catalog:publish`.
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

O Android valida limite, campos/tipos, schema, datas, hash do payload e versão
antes da escrita. `AtomicFile` preserva a versão anterior em falhas de escrita;
o snapshot só muda depois da gravação. Cache na pasta **noBackupFilesDir**:
não se restaura uma base antiga de backup. Falhas de rede, TLS, redirect,
formato ou integridade deixam a última base válida ativa. Ela não expira offline.
Sem cache válido, continua a base embarcada. Reinstalar/limpar os dados também
remove o histórico de versão: a próxima conexão passa a confiar na origem
HTTPS novamente. O checksum do cache é revalidado ao iniciar.

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

- Na raiz do repositório Android: `npm run catalog:test`.
- Na raiz do workspace: `npx tsx --test shared/threat-catalog.test.ts server/threat-catalog.test.ts`.
- No workspace: `npm run check`.
- No workspace: `bash script/test-android-catalog.sh` — testes JVM do parser com
  dependências baixadas para `generated-artifacts/`, sem APKs de malware.
- Compilação Android completa/emulador: requer Android SDK/Gradle e não é
  substituída pelo teste JVM.
- Testes instrumentados de armazenamento alteram catálogo/preferências da
  instalação-alvo; executar somente numa instalação descartável/emulador.
