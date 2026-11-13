# Atualização visual do APK de teste

## Telas e organização

- Boas-vindas: logo original, marca, explicação, começar, reconhecimento/restauração de assinantes e documentos reais.
- Início: identificação do aplicativo, notificações, apresentação Premium, análise real, controle do monitoramento, resumo diário e resultados ativos existentes.
- Ferramentas: links e cache; controles de ligações na ordem solicitada; permissões, notificações, base e histórico; aparência; privacidade por último.
- Links: cabeçalho interno sem logo, voltar, campo, colar, analisar, resultado e orientação. O resultado sem alertas não é apresentado como garantia absoluta.
- Cache: orientação antes da lista, total e horário quando disponíveis, atualização, grade de dois cartões ou coluna única conforme largura/fonte, ícones reais e configurações individuais. O aviso de limitações permanece fora da rolagem.
- Premium: oferta consultada na Play, benefícios reais, compra/restauração e estados de consulta/indisponibilidade/assinante.
- Atendimento: canais reais e dados visíveis, com alternativas quando não houver aplicativo externo.
- Histórico: eventos concluídos e paginados, sem inferir execuções anteriores.
- Sobre: página institucional já configurada, não uma cópia estática.

A navegação inferior tem somente cinco destinos: Início, Ferramentas, Premium, Sobre e Atendimento. A aparência está dentro de Ferramentas.

## Temas

Claro, Preto e dourado e Vinho e dourado compartilham layouts, ícones, textos e funções. A escolha permanece na preferência existente. Os identificadores salvos não foram apagados ou renomeados.

Textos podem quebrar quando o espaço ou a fonte ampliada exigir; não se usa fonte excessivamente pequena para reproduzir um print.

## Conexões funcionais mínimas autorizadas

1. **Eventos locais para estatísticas e histórico.** Registram conclusão de análises manuais, automáticas do serviço, verificações efetivas de instalações e da base, novas detecções e remoções confirmadas pelo caminho existente. A consulta diária usa o dia civil local. Os contadores antigos e seus resets continuam intactos; não alimentam o novo resumo diário. Registros anteriores não são inventados.
2. **Alertas de chamadas independentes.** A nova preferência, ligada por padrão para preservar o comportamento anterior, condiciona os alertas/overlay nos dois serviços existentes. Os critérios de análise e rejeição de chamadas não foram alterados. A interface considera acesso, permissões e disponibilidade de apresentação de alertas, em vez de mostrar apenas a preferência desejada como se já estivesse operando.
3. **Oferta real da Google Play.** A apresentação lê as fases da primeira oferta, a mesma já usada na compra. Sete dias gratuitos só são apresentados se essa oferta contiver a fase correspondente. O teste local não é usado como prova de elegibilidade de oferta da Play. Preços não carregados ficam indisponíveis, com opção de nova consulta. Compras não foram substituídas por links externos.
4. **Novos destinos visuais.** Boas-vindas, Atendimento e Histórico usam atividades nativas; a navegação e o voltar encaminham aos fluxos atuais. A restauração a partir das boas-vindas só encaminha ao início como assinante após confirmação real do fluxo existente.

Não foram acrescentadas permissões, bibliotecas, APIs pagas ou coleta externa.

## Mapeamento dos botões

| Ação | Implementação |
| --- | --- |
| Começar agora | MainActivity existente, sem liberação indevida |
| Já sou assinante | SubscriptionActivity com restauração pelo BillingManager |
| Disponível / Premium | SubscriptionActivity |
| Analisar aplicativos | Fluxo manual atual de MainActivity |
| Ativar/desativar monitoramento | Controle atual e verificações de acesso/permissões |
| Links: colar/analisar | ClipboardManager e LinkChecker existentes |
| Cache: atualizar/abrir | Consulta real do Android e configurações do pacote escolhido |
| Telemarketing | Preferência atual e CallScannerService |
| Alertar ligações suspeitas | Nova preferência de apresentação, sem mudar a rejeição de chamadas |
| Reportar/bloquear número | Diálogo existente em MainActivity |
| Permissões | Revisão atual |
| Notificações | Configurações de notificações do Android |
| Atualizar base | ThreatCatalogUpdater existente, respeitando a disponibilidade atual |
| Histórico | ActivityJournal local, sem detalhes protegidos para quem não tiver acesso |
| Aparência | Preferência existente em GuardianAppearance |
| Sobre | URL institucional existente em GuardianExternal |
| WhatsApp/e-mail/Instagram | Intents reais, com navegador ou cópia como alternativa adequada |
| Termos/privacidade/exclusão de dados | Destinos reais já existentes |

## Verificações e limites

O documento HTML gerado junto ao APK contém as capturas nativas nos três temas, o total efetivo de testes e as limitações.

As verificações de interface usam Robolectric com aplicação Android básica para isolar inicializações de SDKs externos; não são testes físicos nem comprovação de disponibilidade do Google Play Billing no pacote isolado. O workspace não tem KVM.

O APK mantém o sufixo de pacote de teste já existente e o certificado usado nas entregas anteriores. Não se deve remover esse isolamento como forma de simular uma compra na Play. Uma compra real, elegibilidade da conta, chamadas reais, execução sob restrições do aparelho e inicialização física não foram comprovadas neste ambiente.

A frase de boas-vindas “Navegue sem anúncios” foi contextualizada como proteção contra aplicativos que exibem anúncios invasivos. Não foi implementado nem prometido bloqueio geral de publicidade em sites.

Nenhuma alteração foi publicada na Play Store. A versão estável e a referência de recuperação continuam separadas das experiências.
