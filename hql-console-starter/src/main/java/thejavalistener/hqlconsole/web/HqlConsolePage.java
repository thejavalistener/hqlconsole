package thejavalistener.hqlconsole.web;

/**
 * La página de la consola: un único HTML con CSS y JS embebidos.
 *
 * <p>Sin template engine, sin build de frontend y sin CDN: el jar tiene que funcionar aunque la
 * máquina esté sin internet. Los valores que se inyectan son de configuración, no de usuario.</p>
 */
public final class HqlConsolePage
{
	private HqlConsolePage()
	{
	}

	public static String html(String base,String path,int maxRows,boolean allowWrites)
	{
		return TEMPLATE
				.replace("__BASE__",_jsString(base))
				.replace("__PATH__",path)
				.replace("__MAX_ROWS__",String.valueOf(maxRows))
				.replace("__ALLOW_WRITES__",allowWrites?"true":"false");
	}

	/** El valor va dentro de un literal JS entre comillas simples. */
	private static String _jsString(String value)
	{
		return value.replace("\\","\\\\").replace("'","\\'");
	}

	private static final String TEMPLATE =
		"""
		<!DOCTYPE html>
		<html lang="es">
		<head>
		<meta charset="utf-8">
		<meta name="viewport" content="width=device-width, initial-scale=1">
		<title>HQL Console</title>
		<style>
		  :root { color-scheme: light dark; --borde:#d0d4da; --fondo:#f6f7f9; --acento:#1f6feb; --error:#b3261e; }
		  * { box-sizing: border-box; }
		  html, body { height:100%; }
		  body { margin:0; padding:12px; font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
		         background:var(--fondo); display:flex; flex-direction:column; gap:8px; height:100vh; overflow:hidden; }
		  body.arrastrando { user-select:none; cursor:col-resize; }
		  h1 { font-size:16px; margin:0; }
		  .barra { display:flex; align-items:baseline; gap:10px; flex-wrap:wrap; flex:0 0 auto; }
		  .ruta { font-family: ui-monospace, Consolas, monospace; font-size:12px; opacity:.6; }
		  .aviso { font-size:12px; color:var(--error); }
		  .acciones { display:flex; align-items:center; gap:12px; flex-wrap:wrap; flex:0 0 auto; }
		  button { padding:7px 16px; font-size:13px; font-weight:600; color:#fff; background:var(--acento); border:0; border-radius:6px; cursor:pointer; }
		  button:disabled { opacity:.5; cursor:progress; }
		  .estado { font-size:12px; opacity:.75; }
		  .sel { color:var(--acento); opacity:1; font-weight:600; }
		  #error { display:none; flex:0 0 auto; max-height:30vh; overflow:auto; padding:10px;
		           border:1px solid var(--error); border-left-width:4px; border-radius:6px;
		           background:#fff5f4; color:var(--error); font-size:13px; }
		  #error pre { margin:6px 0 0; font-family: ui-monospace, Consolas, monospace; font-size:12px; white-space:pre-wrap; }

		  /* --- el área partida: editor a la izquierda, resultados a la derecha --- */
		  #split { flex:1 1 auto; min-height:0; display:flex; align-items:stretch; --ancho-editor:48%; }
		  #panel-editor { flex:0 0 var(--ancho-editor); display:flex; min-width:0; }
		  #hql { flex:1 1 auto; width:100%; min-height:0; margin:0; padding:10px; background:#fff;
		         font-family: ui-monospace, Consolas, monospace; font-size:13px; line-height:1.5;
		         border:1px solid var(--borde); border-radius:6px; resize:none; tab-size:2; }
		  #divisor { flex:0 0 10px; display:flex; align-items:center; justify-content:center;
		             cursor:col-resize; touch-action:none; background:none; border:0; padding:0; }
		  #divisor::before { content:''; width:3px; height:100%; border-radius:2px; background:var(--borde);
		                     transition:background .12s ease; }
		  #divisor:hover::before, #divisor:focus-visible::before, body.arrastrando #divisor::before { background:var(--acento); }
		  #divisor:focus-visible { outline:2px solid var(--acento); outline-offset:2px; border-radius:4px; }
		  #panel-resultado { flex:1 1 auto; min-width:0; min-height:0; display:flex; flex-direction:column; gap:8px; }
		  .vacio { flex:0 0 auto; padding:16px; text-align:center; color:#8b949e; font-size:13px;
		           border:1px dashed var(--borde); border-radius:6px; }
		  .tabla { flex:1 1 auto; min-height:0; overflow:auto; border:1px solid var(--borde); border-radius:6px; background:#fff; }
		  table { border-collapse:collapse; width:100%; font-size:13px; }
		  th, td { padding:6px 10px; text-align:left; border-bottom:1px solid var(--borde); font-family: ui-monospace, Consolas, monospace; white-space:pre; }
		  th { position:sticky; top:0; background:#eceff3; font-weight:600; }
		  tr:nth-child(even) td { background:#fafbfc; }
		  td.nulo { color:#8b949e; font-style:italic; }
		  .pie { flex:0 0 auto; font-size:12px; opacity:.7; }
		  details { flex:0 0 auto; font-size:12px; }
		  summary { cursor:pointer; opacity:.7; }
		  details pre { background:#fff; border:1px solid var(--borde); border-radius:6px; padding:10px; overflow:auto; max-height:40vh; }

		  /* En pantallas angostas el divisor no tiene sentido: se apilan. */
		  @media (max-width: 720px) {
		    body { height:auto; overflow:auto; }
		    #split { flex-direction:column; }
		    #panel-editor { flex:1 1 auto; }
		    #hql { min-height:200px; }
		    #divisor { display:none; }
		    #panel-resultado { flex:1 1 auto; }
		    .tabla { max-height:60vh; }
		  }
		</style>
		</head>
		<body>
		<div class="barra">
		  <h1>HQL Console</h1>
		  <span class="ruta">__PATH__</span>
		  <span class="aviso">herramienta de desarrollo: ejecuta HQL contra el EntityManager vivo</span>
		</div>
		<div class="acciones">
		  <button id="run" title="Ctrl+Enter">Ejecutar</button>
		  <span class="estado">Ctrl+Enter: sólo la selección; sin selección, todo el texto</span>
		  <span class="estado sel" id="seleccion" hidden></span>
		  <span class="estado" id="estado"></span>
		</div>
		<div id="error"><div id="error-msg"></div><pre id="error-sql"></pre></div>
		<div id="split">
		  <div id="panel-editor">
		    <textarea id="hql" spellcheck="false" placeholder="SELECT e.id, e.nombre FROM Empleado e"></textarea>
		  </div>
		  <div id="divisor" role="separator" aria-orientation="vertical" tabindex="0" aria-label="Redimensionar el editor"
		       title="Arrastra para redimensionar. Flechas: de a 2%. Inicio/Fin: extremos. Doble clic: 50/50."></div>
		  <div id="panel-resultado">
		    <div class="vacio" id="vacio">Los resultados aparecen acá.</div>
		    <div class="tabla" id="tabla" hidden><table id="t"></table></div>
		    <div class="pie" id="pie"></div>
		    <details id="crudo" hidden><summary>JSON crudo</summary><pre id="crudo-pre"></pre></details>
		  </div>
		</div>
		<script>
		const BASE = '__BASE__';
		const MAX_ROWS = __MAX_ROWS__;
		const ALLOW_WRITES = __ALLOW_WRITES__;

		const ta = document.getElementById('hql');
		const btn = document.getElementById('run');
		const estado = document.getElementById('estado');
		const seleccion = document.getElementById('seleccion');
		const cajaError = document.getElementById('error');
		const errorMsg = document.getElementById('error-msg');
		const errorSql = document.getElementById('error-sql');
		const cajaTabla = document.getElementById('tabla');
		const tabla = document.getElementById('t');
		const vacio = document.getElementById('vacio');
		const pie = document.getElementById('pie');
		const crudo = document.getElementById('crudo');
		const crudoPre = document.getElementById('crudo-pre');
		const split = document.getElementById('split');
		const divisor = document.getElementById('divisor');

		const EJEMPLO = 'SELECT e.id, e.nombre, e.salario FROM Empleado e';
		const CLAVE_TEXTO = 'hql-console.consulta';
		const CLAVE_ANCHO = 'hql-console.ancho';

		// localStorage puede tirar (modo privado, cookies de sitio bloqueadas). Si eso pasa, el
		// script entero se caía y con él los listeners: la consola quedaba muerta sin decir por qué.
		// Con este envoltorio nunca tira y, si no hay almacén, al menos dura lo que dura la pestaña.
		const ALMACEN = (function() {
		  try {
		    const prueba = 'hql-console.prueba';
		    window.localStorage.setItem(prueba, '1');
		    window.localStorage.removeItem(prueba);
		    return window.localStorage;
		  } catch (e) {
		    const memoria = new Map();
		    return {
		      getItem: function(k) { return memoria.has(k) ? memoria.get(k) : null; },
		      setItem: function(k, v) { memoria.set(k, String(v)); },
		      removeItem: function(k) { memoria.delete(k); }
		    };
		  }
		})();

		function guardarTexto() {
		  try { ALMACEN.setItem(CLAVE_TEXTO, ta.value); } catch (e) {}
		}

		// Lo que haya guardado se restituye al abrir la página: sobrevive a recargar y a reiniciar
		// la aplicación, porque vive en el navegador y no en el servidor.
		const textoGuardado = ALMACEN.getItem(CLAVE_TEXTO);
		ta.value = (textoGuardado === null || textoGuardado === undefined) ? EJEMPLO : textoGuardado;

		// Se guarda mientras se escribe (con retardo) y también al ejecutar y al cerrar: así no se
		// pierde lo escrito aunque nunca se llegue a ejecutar.
		let temporizador = null;
		ta.addEventListener('input', function() {
		  clearTimeout(temporizador);
		  temporizador = setTimeout(guardarTexto, 400);
		});
		window.addEventListener('pagehide', guardarTexto);
		document.addEventListener('visibilitychange', function() {
		  if (document.visibilityState === 'hidden') { guardarTexto(); }
		});

		// Lo que se ejecuta: la selección si tiene texto, y si no todo el texto.
		function textoAejecutar(valor, inicio, fin) {
		  if (inicio < fin) { return valor.substring(inicio, fin); }
		  return valor;
		}

		function rangoAEjecutar() {
		  const inicio = ta.selectionStart, fin = ta.selectionEnd;
		  const recorte = textoAejecutar(ta.value, inicio, fin);
		  // Una selección de sólo espacios en blanco no es una intención: se ejecuta todo.
		  if (fin > inicio && recorte.trim()) { return { hql: recorte, parcial: true }; }
		  return { hql: ta.value, parcial: false };
		}

		function refrescarSeleccion() {
		  const caracteres = Math.max(0, ta.selectionEnd - ta.selectionStart);
		  if (caracteres > 0) {
		    seleccion.textContent = 'se ejecutará sólo la selección (' + caracteres + ' caracteres)';
		    seleccion.hidden = false;
		  } else {
		    seleccion.hidden = true;
		  }
		}

		btn.addEventListener('click', ejecutar);
		// Sin esto, el mousedown del botón le roba el foco al textarea y el navegador colapsa la
		// selección: el botón terminaba ejecutando todo en vez de lo pintado.
		btn.addEventListener('mousedown', function(ev) { ev.preventDefault(); });
		ta.addEventListener('keydown', function(ev) {
		  if (ev.key === 'Enter' && (ev.ctrlKey || ev.metaKey)) { ev.preventDefault(); ejecutar(); }
		});
		['keyup', 'mouseup', 'select', 'input'].forEach(function(evento) {
		  ta.addEventListener(evento, refrescarSeleccion);
		});
		document.addEventListener('selectionchange', function() {
		  if (document.activeElement === ta) { refrescarSeleccion(); }
		});
		refrescarSeleccion();

		// ==================== el divisor movible ====================
		const ANCHO_MIN = 15, ANCHO_MAX = 85, ANCHO_POR_DEFECTO = 48;

		function limitar(pct) { return Math.max(ANCHO_MIN, Math.min(ANCHO_MAX, pct)); }

		function aplicarAncho(pct, persistir) {
		  const valor = limitar(pct);
		  split.style.setProperty('--ancho-editor', valor + '%');
		  divisor.setAttribute('aria-valuenow', String(Math.round(valor)));
		  divisor.setAttribute('aria-valuemin', String(ANCHO_MIN));
		  divisor.setAttribute('aria-valuemax', String(ANCHO_MAX));
		  if (persistir) { try { ALMACEN.setItem(CLAVE_ANCHO, String(valor)); } catch (e) {} }
		  return valor;
		}

		let anchoActual = ANCHO_POR_DEFECTO;
		const anchoGuardado = parseFloat(ALMACEN.getItem(CLAVE_ANCHO));
		anchoActual = aplicarAncho(isNaN(anchoGuardado) ? ANCHO_POR_DEFECTO : anchoGuardado, false);

		let arrastrando = false;

		function anchoSegunPuntero(clientX) {
		  const caja = split.getBoundingClientRect();
		  if (caja.width <= 0) { return anchoActual; }
		  return ((clientX - caja.left) / caja.width) * 100;
		}

		divisor.addEventListener('pointerdown', function(ev) {
		  arrastrando = true;
		  try { divisor.setPointerCapture(ev.pointerId); } catch (e) {}
		  document.body.classList.add('arrastrando');
		  anchoActual = aplicarAncho(anchoSegunPuntero(ev.clientX), false);
		  ev.preventDefault();
		});
		divisor.addEventListener('pointermove', function(ev) {
		  if (!arrastrando) { return; }
		  anchoActual = aplicarAncho(anchoSegunPuntero(ev.clientX), false);
		});
		function soltar(ev) {
		  if (!arrastrando) { return; }
		  arrastrando = false;
		  document.body.classList.remove('arrastrando');
		  try { divisor.releasePointerCapture(ev.pointerId); } catch (e) {}
		  anchoActual = aplicarAncho(anchoSegunPuntero(ev.clientX), true);
		}
		divisor.addEventListener('pointerup', soltar);
		divisor.addEventListener('pointercancel', function() {
		  arrastrando = false;
		  document.body.classList.remove('arrastrando');
		});
		divisor.addEventListener('dblclick', function() { anchoActual = aplicarAncho(ANCHO_POR_DEFECTO, true); });
		divisor.addEventListener('keydown', function(ev) {
		  const paso = ev.shiftKey ? 10 : 2;
		  if (ev.key === 'ArrowLeft') { anchoActual = aplicarAncho(anchoActual - paso, true); }
		  else if (ev.key === 'ArrowRight') { anchoActual = aplicarAncho(anchoActual + paso, true); }
		  else if (ev.key === 'Home') { anchoActual = aplicarAncho(ANCHO_MIN, true); }
		  else if (ev.key === 'End') { anchoActual = aplicarAncho(ANCHO_MAX, true); }
		  else { return; }
		  ev.preventDefault();
		});

		// ==================== ejecutar ====================
		async function ejecutar() {
		  // Se guarda el contenido del editor, no lo último ejecutado: es el estado del textarea.
		  guardarTexto();
		  const rango = rangoAEjecutar();
		  const hql = rango.hql.trim();
		  if (!hql) {
		    mostrarError({ error: 'No hay nada que ejecutar: el área está vacía o la selección no tiene texto.' });
		    return;
		  }
		  btn.disabled = true;
		  cajaError.style.display = 'none';
		  estado.textContent = (rango.parcial ? 'Ejecutando la selección...' : 'Ejecutando todo el texto...');
		  try {
		    const respuesta = await fetch(BASE + '/api/execute', {
		      method: 'POST',
		      headers: { 'Content-Type': 'application/json' },
		      body: JSON.stringify({ hql: hql })
		    });
		    let datos;
		    try {
		      datos = await respuesta.json();
		    } catch (e) {
		      datos = { error: 'El servidor respondio HTTP ' + respuesta.status + ' sin JSON.' };
		    }
		    if (!respuesta.ok) { mostrarError(datos); return; }
		    mostrarResultado(datos);
		  } catch (e) {
		    mostrarError({ error: 'No se pudo contactar la consola: ' + e });
		  } finally {
		    btn.disabled = false;
		  }
		}

		function mostrarError(datos) {
		  errorMsg.textContent = datos.error || 'Error desconocido.';
		  errorSql.textContent = datos.cause ? ('causa: ' + datos.cause) : (datos.statement || '');
		  cajaError.style.display = 'block';
		  cajaTabla.hidden = true;
		  vacio.hidden = true;
		  crudo.hidden = true;
		  estado.textContent = '';
		  pie.textContent = '';
		}

		function mostrarResultado(datos) {
		  crudoPre.textContent = JSON.stringify(datos, null, 2);
		  crudo.hidden = false;
		  vacio.hidden = true;

		  if (datos.type === 'DML') {
		    cajaTabla.hidden = true;
		    pie.textContent = '';
		    const detalle = datos.message ? datos.message : (datos.affectedRows + ' fila(s) afectada(s)');
		    estado.textContent = detalle + ' en ' + datos.elapsedMs + ' ms';
		    return;
		  }

		  const cabeceras = (datos.headers && datos.headers.length) ? datos.headers : cabecerasDeFilas(datos.rows);
		  tabla.textContent = '';

		  const thead = document.createElement('thead');
		  const filaCabeceras = document.createElement('tr');
		  cabeceras.forEach(function(c) {
		    const th = document.createElement('th');
		    th.textContent = c;
		    filaCabeceras.appendChild(th);
		  });
		  thead.appendChild(filaCabeceras);
		  tabla.appendChild(thead);

		  const tbody = document.createElement('tbody');
		  (datos.rows || []).forEach(function(fila) {
		    const tr = document.createElement('tr');
		    fila.forEach(function(celda) {
		      const td = document.createElement('td');
		      if (celda === null || celda === undefined) {
		        td.textContent = 'NULL';
		        td.className = 'nulo';
		      } else {
		        td.textContent = String(celda);
		      }
		      tr.appendChild(td);
		    });
		    tbody.appendChild(tr);
		  });
		  tabla.appendChild(tbody);
		  cajaTabla.hidden = false;

		  let resumen = datos.rowCount + ' fila' + (datos.rowCount === 1 ? '' : 's') + ' en ' + datos.elapsedMs + ' ms';
		  if (datos.truncated) { resumen = resumen + ' - truncado a ' + MAX_ROWS + ' filas'; }
		  if (datos.message) { resumen = resumen + ' - ' + datos.message; }
		  estado.textContent = resumen;
		  pie.textContent = ALLOW_WRITES
		    ? 'escrituras habilitadas (hql-console.allow-writes=true)'
		    : 'solo lectura (hql-console.allow-writes=false)';
		}

		function cabecerasDeFilas(filas) {
		  if (!filas || !filas.length) { return []; }
		  const salida = [];
		  for (let i = 0; i < filas[0].length; i++) { salida.push('col' + (i + 1)); }
		  return salida;
		}
		</script>
		</body>
		</html>
		""";
}
