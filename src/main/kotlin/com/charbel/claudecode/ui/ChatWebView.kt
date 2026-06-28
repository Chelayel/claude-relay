package com.charbel.claudecode.ui

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.UIUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Color
import javax.swing.JComponent

/**
 * Rich chat transcript rendered in an embedded Chromium (JCEF) view. Messages
 * are pushed from Kotlin by executing small JS calls against a `cc` API defined
 * in the page. Colors are pulled from the active IDE theme.
 */
class ChatWebView(parent: Disposable) : ChatView {

    private val gson = Gson()
    // Off-screen rendering: a lightweight Swing component that tracks layout
    // bounds. The default windowed browser embeds a heavyweight native surface
    // that fails to resize inside a tool window (worst in a detached/floating
    // window), leaving the page stuck at its initial size.
    private val browser = JBCefBrowser.createBuilder()
        .setOffScreenRendering(true)
        .build()
    private val pending = ArrayDeque<String>()
    @Volatile private var ready = false

    override val component: JComponent get() = browser.component

    init {
        Disposer.register(parent, browser)
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                synchronized(pending) {
                    ready = true
                    pending.forEach { run(it) }
                    pending.clear()
                }
            }
        }, browser.cefBrowser)
        browser.loadHTML(document())
    }

    private fun exec(js: String) {
        synchronized(pending) {
            if (ready) run(js) else pending.add(js)
        }
    }

    private fun run(js: String) = browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)

    private fun q(s: String): String = gson.toJson(s)

    override fun clear() = exec("cc.clear()")
    override fun addUser(text: String) = exec("cc.user(${q(text)})")
    override fun assistantChunk(text: String) = exec("cc.assistant(${q(text)})")
    override fun endAssistant() = exec("cc.endAssistant()")
    override fun addThinking(text: String) = exec("cc.thinking(${q(text)})")
    override fun addToolUse(name: String, summary: String) = exec("cc.tool(${q(name)},${q(summary)})")
    override fun addToolResult(text: String, isError: Boolean) = exec("cc.toolResult(${q(text)},$isError)")
    override fun addSystem(text: String) = exec("cc.system(${q(text)})")
    override fun addError(text: String) = exec("cc.error(${q(text)})")
    override fun setBusy(busy: Boolean) = exec("cc.busy($busy)")
    override fun setHomeContext(html: String?) = exec("cc.home(${html?.let { q(it) } ?: "null"})")

    // ---- page ----------------------------------------------------------------

    private fun document(): String {
        val scheme0 = EditorColorsManager.getInstance().globalScheme
        val pageBg = scheme0.defaultBackground            // editor bg (darker than panel in most themes)
        val surface = UIUtil.getPanelBackground()         // panel bg — used for bubbles so they pop
        val fgColor = UIUtil.getLabelForeground()
        val bg = hex(pageBg)
        val fg = hex(fgColor)
        val dim = hex(blend(fgColor, pageBg, 0.42f))
        val border = hex(blend(pageBg, fgColor, if (dark()) 0.22f else 0.16f))
        val accent = "#D97757"
        val assistantBubble = hex(raise(surface, if (dark()) 0.14f else 0.09f))
        val userBubble = hex(blend(pageBg, Color(0xD9, 0x77, 0x57), if (dark()) 0.30f else 0.18f))
        val codeBg = hex(blend(pageBg, fgColor, if (dark()) 0.16f else 0.08f))
        val scheme = EditorColorsManager.getInstance().globalScheme
        val codeFont = scheme.editorFontName
        val uiFont = UIUtil.getLabelFont()
        val fontFamily = uiFont.family
        val fontSize = uiFont.size

        val vars = ":root{" +
            "--bg:$bg;--fg:$fg;--dim:$dim;--border:$border;--accent:$accent;" +
            "--abubble:$assistantBubble;--ububble:$userBubble;--code:$codeBg;" +
            "--font:'$fontFamily';--codefont:'$codeFont';--fs:${fontSize}px;}"

        return "<!doctype html><html><head><meta charset='utf-8'>" +
            "<meta name='color-scheme' content='${if (dark()) "dark" else "light"}'>" +
            "<style>$vars$CSS</style></head><body>" +
            "<div id='empty'><div class='spark'>$CLAUDE_SVG</div>" +
            "<div class='etitle'>Claude Code</div>" +
            "<div class='esub'>Ask about this project, request changes, or run a task.</div>" +
            "<div id='home'></div></div>" +
            "<div id='chat'></div>" +
            "<div id='typing' class='row in'><div class='avatar claude'>$CLAUDE_SVG</div>" +
            "<div class='bubble typing'><span></span><span></span><span></span></div></div>" +
            "<script>var CLAUDE_SVG=${gson.toJson(CLAUDE_SVG)};var USER_SVG=${gson.toJson(USER_SVG)};$JS</script>" +
            "</body></html>"
    }

    private fun dark() = ColorUtil.isDark(UIUtil.getPanelBackground())
    private fun hex(c: Color) = "#" + ColorUtil.toHex(c)
    /** Nudge a surface color away from the background so bubbles stand out. */
    private fun raise(base: Color, amt: Float): Color = blend(base, if (dark()) Color.WHITE else Color.BLACK, amt)
    private fun blend(a: Color, b: Color, r: Float): Color {
        val t = r.coerceIn(0f, 1f)
        return Color(
            (a.red * (1 - t) + b.red * t).toInt(),
            (a.green * (1 - t) + b.green * t).toInt(),
            (a.blue * (1 - t) + b.blue * t).toInt(),
        )
    }

    companion object {
        private val CLAUDE_SVG = """
            <svg viewBox='0 0 16 16' fill='none'><g stroke='#fff' stroke-width='1.3' stroke-linecap='round'>
            <line x1='9.6' y1='8' x2='14.6' y2='8'/><line x1='9.4' y1='8.8' x2='13.7' y2='11.3'/>
            <line x1='8.8' y1='9.4' x2='11.3' y2='13.7'/><line x1='8' y1='9.6' x2='8' y2='14.6'/>
            <line x1='7.2' y1='9.4' x2='4.7' y2='13.7'/><line x1='6.6' y1='8.8' x2='2.3' y2='11.3'/>
            <line x1='6.4' y1='8' x2='1.4' y2='8'/><line x1='6.6' y1='7.2' x2='2.3' y2='4.7'/>
            <line x1='7.2' y1='6.6' x2='4.7' y2='2.3'/><line x1='8' y1='6.4' x2='8' y2='1.4'/>
            <line x1='8.8' y1='6.6' x2='11.3' y2='2.3'/><line x1='9.4' y1='7.2' x2='13.7' y2='4.7'/></g></svg>
        """.trimIndent()

        private val USER_SVG = """
            <svg viewBox='0 0 16 16' fill='none'><circle cx='8' cy='5.5' r='2.6' fill='currentColor'/>
            <path d='M2.6 14c0-3 2.4-4.8 5.4-4.8s5.4 1.8 5.4 4.8' fill='currentColor'/></svg>
        """.trimIndent()

        private val CSS = """
            *{box-sizing:border-box;-webkit-user-select:text}
            *:focus{outline:none}
            html,body{margin:0;padding:0;background:var(--bg);color:var(--fg);
              font-family:var(--font),system-ui,sans-serif;font-size:var(--fs);line-height:1.5;
              overflow-x:hidden}
            #empty{position:fixed;inset:0;display:flex;flex-direction:column;align-items:center;
              justify-content:center;text-align:center;padding:24px;gap:6px;opacity:.85}
            #empty .spark{width:38px;height:38px;border-radius:50%;display:flex;align-items:center;
              justify-content:center;background:linear-gradient(135deg,#E08763,#C75F3E);margin-bottom:6px}
            #empty .spark svg{width:22px;height:22px}
            #empty .etitle{font-weight:600;font-size:1.05em}
            #empty .esub{color:var(--dim);max-width:240px}
            #home{margin-top:18px;display:flex;flex-direction:column;gap:14px;max-width:300px;width:100%}
            #home:empty{display:none}
            .hsec .hhdr{font-size:.72em;font-weight:600;letter-spacing:.06em;text-transform:uppercase;
              color:var(--dim);margin-bottom:7px;text-align:left}
            .hchips{display:flex;flex-wrap:wrap;gap:6px;justify-content:flex-start}
            .hchip{display:inline-flex;align-items:center;gap:5px;padding:4px 9px;border-radius:999px;
              border:1px solid var(--border);background:var(--abubble);font-size:.84em;cursor:default}
            .hchip .dot{width:6px;height:6px;border-radius:50%;background:var(--accent);flex:0 0 6px}
            #chat{padding:12px 12px 4px}
            .row{display:flex;gap:8px;margin:10px 0;align-items:flex-start}
            .row.out{flex-direction:row-reverse}
            .avatar{flex:0 0 24px;width:24px;height:24px;border-radius:50%;display:flex;
              align-items:center;justify-content:center;margin-top:2px}
            .avatar svg{width:15px;height:15px}
            .avatar.claude{background:linear-gradient(135deg,#E08763,#C75F3E)}
            .avatar.user{background:color-mix(in srgb,var(--fg) 22%,transparent);color:var(--dim)}
            .bubble{max-width:84%;padding:8px 11px;border-radius:13px;border:1px solid var(--border);
              background:var(--abubble);overflow-wrap:anywhere;animation:pop .14s ease}
            .row.in .bubble{border-top-left-radius:4px}
            .row.out .bubble{border-top-right-radius:4px;background:var(--ububble);
              border-color:color-mix(in srgb,var(--accent) 35%,var(--border))}
            .name{font-size:11px;font-weight:600;color:var(--accent);margin-bottom:3px;letter-spacing:.02em}
            .md>*:first-child{margin-top:0}.md>*:last-child{margin-bottom:0}
            .md p{margin:6px 0}.md ul,.md ol{margin:6px 0;padding-left:20px}.md li{margin:2px 0}
            .md h1,.md h2,.md h3{margin:10px 0 6px;font-size:1.05em}
            .md a{color:var(--accent)}
            .md code{font-family:var(--codefont),monospace;background:var(--code);
              padding:1px 5px;border-radius:5px;font-size:.92em}
            .md pre{background:var(--code);padding:9px 11px;border-radius:9px;overflow-x:auto;margin:7px 0;
              border:1px solid var(--border)}
            .md pre code{background:none;padding:0;font-size:.9em;line-height:1.45}
            .tool{display:flex;align-items:center;gap:7px;margin:7px 4px;color:var(--dim);font-size:.9em}
            .tool .gear{flex:0 0 16px;width:16px;height:16px;border-radius:5px;
              background:color-mix(in srgb,var(--accent) 18%,transparent);display:flex;
              align-items:center;justify-content:center;color:var(--accent);font-size:11px}
            .tool b{color:var(--fg);font-weight:600}.tool .arg{opacity:.85;overflow:hidden;
              text-overflow:ellipsis;white-space:nowrap}
            details.tres{margin:4px 4px 4px 30px;font-size:.86em}
            details.tres summary{color:var(--dim);cursor:pointer;list-style:none;padding:2px 0}
            details.tres summary::-webkit-details-marker{display:none}
            details.tres summary:before{content:'\25B8 ';color:var(--dim)}
            details.tres[open] summary:before{content:'\25BE '}
            details.tres pre{background:var(--code);border:1px solid var(--border);border-radius:8px;
              padding:8px 10px;margin:4px 0;overflow-x:auto;font-family:var(--codefont),monospace;
              white-space:pre-wrap;max-height:240px;overflow-y:auto}
            details.tres.err summary{color:#D26B66}
            .thinking{margin:6px 30px;color:var(--dim);font-style:italic;font-size:.92em;white-space:pre-wrap}
            .system{text-align:center;color:var(--dim);font-size:.82em;margin:8px 0}
            .errbox{margin:8px 4px;padding:8px 11px;border-radius:9px;font-size:.92em;
              background:color-mix(in srgb,#D26B66 16%,var(--bg));border:1px solid color-mix(in srgb,#D26B66 45%,var(--border));color:#E08C87}
            .typing{display:inline-flex;gap:4px;align-items:center;padding:11px 13px}
            #typing{display:none;padding:0 12px}
            .typing span{width:6px;height:6px;border-radius:50%;background:var(--dim);
              animation:blink 1.2s infinite}
            .typing span:nth-child(2){animation-delay:.2s}.typing span:nth-child(3){animation-delay:.4s}
            @keyframes blink{0%,60%,100%{opacity:.25;transform:translateY(0)}30%{opacity:1;transform:translateY(-3px)}}
            @keyframes pop{from{opacity:0;transform:translateY(3px)}to{opacity:1;transform:none}}
            ::-webkit-scrollbar{width:8px;height:0}
            ::-webkit-scrollbar-track{background:transparent}
            ::-webkit-scrollbar-thumb{background:var(--border);border-radius:6px}
        """.trimIndent()

        private val JS = """
            var chat=document.getElementById('chat');
            var cur=null,curRaw='';
            function scrollBot(){window.scrollTo(0,document.body.scrollHeight);}
            function add(n){var e=document.getElementById('empty');if(e)e.style.display='none';
              chat.appendChild(n);scrollBot();return n;}
            function esc(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}
            function inl(s){
              s=esc(s);
              s=s.replace(/`([^`]+)`/g,function(m,a){return '<code>'+a+'</code>';});
              s=s.replace(/\*\*([^*]+)\*\*/g,function(m,a){return '<b>'+a+'</b>';});
              s=s.replace(/(^|[^*])\*([^*\n]+)\*/g,function(m,p,a){return p+'<i>'+a+'</i>';});
              s=s.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g,function(m,t,u){return '<a href="'+u+'">'+t+'</a>';});
              return s;
            }
            function md(src){
              var parts=src.split('```'),out='';
              for(var i=0;i<parts.length;i++){
                if(i%2===1){
                  var c=parts[i],nl=c.indexOf('\n');
                  if(nl>=0){var f=c.substring(0,nl).trim();
                    if(f&&f.indexOf(' ')<0&&f.length<20)c=c.substring(nl+1);}
                  out+='<pre><code>'+esc(c.replace(/\n${'$'}/,''))+'</code></pre>';
                }else{out+=block(parts[i]);}
              }
              return out;
            }
            function block(t){
              var lines=t.split('\n'),html='',list=null,para=[];
              function flushP(){if(para.length){html+='<p>'+inl(para.join('\n')).replace(/\n/g,'<br>')+'</p>';para=[];}}
              function flushL(){if(list){html+='</'+list+'>';list=null;}}
              for(var i=0;i<lines.length;i++){
                var ln=lines[i];
                var h=ln.match(/^(#{1,3})\s+(.*)/);
                var ul=ln.match(/^\s*[-*]\s+(.*)/);
                var ol=ln.match(/^\s*\d+\.\s+(.*)/);
                if(h){flushP();flushL();html+='<h'+h[1].length+'>'+inl(h[2])+'</h'+h[1].length+'>';}
                else if(ul){flushP();if(list!=='ul'){flushL();list='ul';html+='<ul>';}html+='<li>'+inl(ul[1])+'</li>';}
                else if(ol){flushP();if(list!=='ol'){flushL();list='ol';html+='<ol>';}html+='<li>'+inl(ol[1])+'</li>';}
                else if(ln.trim()===''){flushP();flushL();}
                else{flushL();para.push(ln);}
              }
              flushP();flushL();return html;
            }
            function avatar(k){var a=document.createElement('div');a.className='avatar '+k;
              a.innerHTML=k==='claude'?CLAUDE_SVG:USER_SVG;return a;}
            function openA(){
              var r=document.createElement('div');r.className='row in';
              r.appendChild(avatar('claude'));
              var b=document.createElement('div');b.className='bubble';
              b.innerHTML="<div class='name'>Claude</div><div class='md'></div>";
              r.appendChild(b);add(r);cur=b;curRaw='';
            }
            var cc={
              user:function(t){cc.endAssistant();
                var r=document.createElement('div');r.className='row out';
                var b=document.createElement('div');b.className='bubble';b.innerHTML="<div class='md'>"+md(t)+"</div>";
                r.appendChild(b);r.appendChild(avatar('user'));add(r);},
              assistant:function(t){if(!cur)openA();curRaw+=t;
                cur.querySelector('.md').innerHTML=md(curRaw);scrollBot();},
              endAssistant:function(){cur=null;curRaw='';},
              thinking:function(t){cc.endAssistant();var d=document.createElement('div');
                d.className='thinking';d.textContent=t;add(d);},
              tool:function(name,arg){cc.endAssistant();var d=document.createElement('div');d.className='tool';
                d.innerHTML="<span class='gear'>⚙</span><b></b><span class='arg'></span>";
                d.querySelector('b').textContent=name;d.querySelector('.arg').textContent=arg;add(d);},
              toolResult:function(t,err){var det=document.createElement('details');
                det.className='tres'+(err?' err':'');
                var s=document.createElement('summary');s.textContent=err?'Error output':'Output';
                var p=document.createElement('pre');p.textContent=t;
                det.appendChild(s);det.appendChild(p);add(det);},
              system:function(t){var d=document.createElement('div');d.className='system';d.textContent=t;add(d);},
              error:function(t){cc.endAssistant();var d=document.createElement('div');d.className='errbox';
                d.textContent=t;add(d);},
              busy:function(on){var ty=document.getElementById('typing');
                ty.style.display=on?'flex':'none';if(on)scrollBot();},
              home:function(h){var e=document.getElementById('home');if(e)e.innerHTML=h||'';},
              clear:function(){chat.innerHTML='';cur=null;curRaw='';
                var e=document.getElementById('empty');if(e)e.style.display='flex';}
            };
        """.trimIndent()
    }
}
