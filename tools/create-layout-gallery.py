"""Package real native-rendered previews into an offline review document."""
import base64
import html
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
output = root / "generated-artifacts/guardian-redesign"
output.mkdir(parents=True, exist_ok=True)
screens = output / "screenshots"
screens.mkdir(exist_ok=True)
source = Path("/tmp/guardian-redesign")
themes = {"original": "Preto e dourado", "petroleo": "Claro", "gold": "Vinho e dourado"}
pages = {
    "boas-vindas": "Boas-vindas", "inicio": "Início", "ferramentas": "Ferramentas e ajustes",
    "links": "Verificar link suspeito", "cache": "Cache por aplicativo",
    "premium": "Premium", "atendimento": "Atendimento", "historico": "Histórico",
}
cards = []
for theme, theme_name in themes.items():
    for page, page_name in pages.items():
        image = source / f"{theme}-{page}.png"
        if not image.exists():
            raise RuntimeError(f"Captura nativa ausente: {image.name}")
        (screens / image.name).write_bytes(image.read_bytes())
        encoded = base64.b64encode(image.read_bytes()).decode("ascii")
        full = source / f"{theme}-{page}-conteudo.png"
        extra = ""
        if full.exists() and full.stat().st_mtime >= image.stat().st_mtime:
            (screens / full.name).write_bytes(full.read_bytes())
            full_encoded = base64.b64encode(full.read_bytes()).decode("ascii")
            extra = f'<details><summary>Ver todo o conteúdo rolável</summary><img src="data:image/png;base64,{full_encoded}" alt="Conteúdo completo de {html.escape(page_name)}"></details>'
        cards.append(
            f'<figure data-theme="{theme}"><figcaption>{html.escape(page_name)}'
            f'<small>{html.escape(theme_name)}</small></figcaption>'
            f'<a href="data:image/png;base64,{encoded}" download="{image.name}">'
            f'<img src="data:image/png;base64,{encoded}" alt="{html.escape(page_name)} — {html.escape(theme_name)}"></a>{extra}</figure>'
        )
comparisons = []
for page, reference_name, label in [
    ("inicio", "image_1794600269812.png", "Resumo diário compacto"),
    ("cache", "image_1794600193688.png", "Cache por aplicativo compacto"),
]:
    theme = "original"
    reference = base64.b64encode((root / "attached_assets" / reference_name).read_bytes()).decode("ascii")
    actual = base64.b64encode((source / f"{theme}-{page}.png").read_bytes()).decode("ascii")
    comparisons.append(
        f'<figure data-theme="{theme}"><figcaption>{label}<small>Referência enviada / APK corrigido</small></figcaption>'
        f'<div class="pair"><img src="data:image/png;base64,{reference}" alt="Referência enviada pelo usuário">'
        f'<img src="data:image/png;base64,{actual}" alt="Tela renderizada no APK 1.6.27"></div></figure>'
    )
totals = dict(tests=0, failures=0, errors=0, skipped=0)
result_source = root / "android-native/app/build/test-results/testDebugUnitTest"
for file in result_source.glob("TEST-*.xml"):
    suite = ET.parse(file).getroot()
    for key in totals:
        totals[key] += int(suite.attrib.get(key, "0"))
mapping = [
    ("Começar agora", "Abre o início; não cria conta nem libera Premium."),
    ("Já sou assinante / Restaurar compra", "Consulta as compras pelo Google Play Billing existente."),
    ("Disponível / Premium", "Abre a página de assinatura e a compra real pela Play."),
    ("Analisar aplicativos", "Usa a análise manual atual e os mesmos critérios e restrições."),
    ("Ativar / Desativar monitoramento", "Usa a ativação atual e a verificação de permissões."),
    ("Verificar link suspeito", "Mantém o analisador local; não abre o endereço."),
    ("Cache: Atualizar valores / Abrir", "Consulta o Android e abre as configurações do aplicativo escolhido."),
    ("Bloquear telemarketing", "Usa a preferência e a triagem de chamadas existentes."),
    ("Alertar ligações suspeitas", "Controla somente alertas, preservando os critérios e bloqueios."),
    ("Reportar ou bloquear número", "Abre o diálogo real existente."),
    ("Permissões / notificações", "Abre a revisão existente ou as configurações reais do Android."),
    ("Atualizar base / detalhes", "Usa a atualização e a validação atuais, sem mudar fontes ou intervalos."),
    ("Histórico de análises", "Mostra eventos locais reais, com paginação e sem detalhes protegidos para não assinantes."),
    ("Escolher tema", "Salva a preferência existente e aplica o tema às telas internas."),
    ("Sobre", "Abre a página institucional já configurada."),
    ("Atendimento", "Abre WhatsApp, e-mail e Instagram; oferece copiar o endereço se não houver aplicativo compatível."),
]
rows = "".join(f"<tr><td>{html.escape(a)}</td><td>{html.escape(b)}</td></tr>" for a, b in mapping)
document = """<!doctype html><html lang="pt-BR"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>M&A Guardian — revisão do layout</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#faf7f0;color:#25211b;font:16px/1.6 system-ui,sans-serif}
header,main{max-width:1200px;margin:auto;padding:24px}header{padding-bottom:0}h1{line-height:1.2}
.note{padding:18px;background:#fff1cc;border:1px solid #d2b773;border-radius:12px}
nav{display:flex;gap:8px;flex-wrap:wrap;padding:16px 0;position:sticky;top:0;background:#faf7f0;z-index:1}
button{font:inherit;padding:10px 16px;border:1px solid #8a6425;border-radius:24px;background:white;color:#543a12;cursor:pointer}
button[aria-pressed=true]{background:#5a1326;color:white;border-color:#5a1326}
.gallery{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:20px}
.pair{display:grid;grid-template-columns:1fr 1fr;gap:8px;align-items:start}
figure{margin:0;padding:12px;border:1px solid #ded2bb;background:white;border-radius:18px}
figcaption{font-weight:700;margin-bottom:10px}small{display:block;color:#68605a;font-weight:400}
img{display:block;width:100%;height:auto;border-radius:10px}
table{width:100%;border-collapse:collapse}td,th{text-align:left;padding:12px;border-bottom:1px solid #ddd;vertical-align:top}
section{margin-top:32px}a{color:#70132d}code{background:#eee8df;padding:2px 5px}
@media(max-width:800px){.gallery{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:520px){.gallery{grid-template-columns:1fr}header,main{padding:16px}}
</style>
<header><h1>M&amp;A Guardian</h1><p>Controles de proteção — APK de teste 1.6.27 (65).</p>
<div class="note"><b>O que estas imagens comprovam:</b> são capturas renderizadas dos componentes Android implementados,
não montagens dos anexos nem testes em aparelho físico. Mostram o primeiro trecho de cada tela; o restante continua acessível por rolagem.
O preço de R$ 9,99/mês é identificado como referência quando a Play não retorna uma oferta. Listas vazias e eventuais contadores refletem o ambiente automatizado e os eventos de teste nele executados;
não são medições do seu celular nem ofertas ou valores comerciais inventados.</div></header>
<main><details open><summary>Comparar o início e o cache com suas referências</summary>
<p>A referência inclui a moldura do celular; a imagem do APK mostra os componentes nativos, sem moldura. Os três temas compartilham as mesmas dimensões. Com fonte ampliada ou nomes compridos, os cartões podem crescer para não cortar conteúdo.</p>
<div class="gallery">COMPARISONS</div></details><nav aria-label="Filtrar tema">
<button aria-pressed="true" data-filter="all">Comparar todos</button>
<button aria-pressed="false" data-filter="original">Preto e dourado</button>
<button aria-pressed="false" data-filter="petroleo">Claro</button>
<button aria-pressed="false" data-filter="gold">Vinho e dourado</button></nav>
<div class="gallery">CARDS</div>
<section><h2>Botões e funcionalidades</h2><table><thead><tr><th>Ação</th><th>Destino real</th></tr></thead><tbody>ROWS</tbody></table></section>
<section><h2>Verificações executadas</h2><p>RESULTS</p>
<p>Esta revisão executa verificações focadas nos controles de proteção, bloqueio para não assinantes, ativação e desativação para assinantes, cores de estado, cartões compactos e acessibilidade.
Não se repetiu a suíte completa de detecção: o detector e os serviços não foram alterados.
A ressalva da revisão anterior sobre um teste de detecção em lote continua registrada em layout-update.md e não é declarada resolvida por estes testes visuais.
Estas capturas não comprovam funcionamento em aparelho físico.</p>
<p>Compilação do APK de teste, testes nativos automatizados, abertura das telas em Android 8 e Android 14 simulados,
contraste, navegação, persistência de tema, textos em telas pequenas/fontes ampliadas, análise de links,
detecção, instalação de aplicativos sinalizados, registros diários e leitura de ofertas de compra foram verificados conforme o relatório de testes.</p>
<p>Os testes de interface usam a aplicação básica do Android para isolar os SDKs externos. Não equivalem à inicialização completa em um celular.</p></section>
<section><h2>Diferenças e limites</h2><ul>
<li>O resumo diário começa a registrar eventos nesta atualização; não reconstrói análises antigas. Os contadores e dados anteriores não foram convertidos nem apagados.</li>
<li>Remoções são registradas somente pelo caminho já existente de confirmação de desinstalação. “Manter” não conta como remoção.</li>
<li>O histórico novo não expõe nomes de ameaças para usuários sem o acesso exigido pelo aplicativo.</li>
<li>Sete dias grátis e preço só são exibidos como oferta quando a Google Play retorna os dados correspondentes. O teste local não comprova elegibilidade de oferta da Play.</li>
<li>O APK usa o pacote isolado de teste já existente. Uma compra real, a elegibilidade da conta e uma atualização da Play exigem a distribuição de teste apropriada pela Google Play; não foram comprovadas por estas capturas.</li>
<li>A conferência de links continua sendo local. Não foi acrescentado um serviço de reputação on-line nem uma garantia absoluta de segurança.</li>
<li>“Navegue sem anúncios” foi contextualizado como proteção contra aplicativos com anúncios invasivos. Não foi inventado bloqueio geral de anúncios de sites.</li>
<li>Permissões, comportamento sob economia de bateria, chamadas reais e inicialização física não foram testados neste aparelho do usuário. Não foram alterados critérios de detecção ou agendamentos.</li>
<li>Não houve publicação em produção, troca da logo, produto de assinatura, pacote publicado, certificado, fontes de análise ou novas permissões.</li>
</ul></section></main><script>
document.querySelectorAll('[data-filter]').forEach(button=>button.addEventListener('click',()=>{
document.querySelectorAll('[data-filter]').forEach(b=>b.setAttribute('aria-pressed',String(b===button)));
document.querySelectorAll('figure').forEach(card=>{card.hidden=button.dataset.filter!=='all'&&card.dataset.theme!==button.dataset.filter});
}));
</script></html>"""
result_text = f"{totals['tests']} testes; {totals['failures']} falhas; {totals['errors']} erros; {totals['skipped']} ignorados."
document = document.replace("CARDS", "".join(cards)).replace("COMPARISONS", "".join(comparisons)).replace("ROWS", rows).replace("RESULTS", result_text)
(output / "MA-Guardian-previas-e-validacao.html").write_text(document, encoding="utf-8")
print(result_text)
print(output / "MA-Guardian-previas-e-validacao.html")
