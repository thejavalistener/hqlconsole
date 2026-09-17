<#
  Verificación end-to-end de la consola HQL.

  Compila el proyecto, levanta el fat jar del demo (con el starter adentro de BOOT-INF/lib,
  que es el escenario real de deploy) y comprueba la página y el endpoint contra una H2 en
  memoria. No necesita nada instalado más que un JDK.

  Uso:
    .\verify-demo.ps1                                  # 18080, sin context-path, tope 500
    .\verify-demo.ps1 -ContextPath /demo -MaxRows 3    # prueba context-path y truncado
#>
param(
    [int]$Port = 18080,
    [string]$ContextPath = '',
    [int]$MaxRows = 500
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# En este workspace el home de Gradle vive al lado del proyecto (el sandbox no deja escribir ~/.gradle).
$gradleHome = Join-Path (Split-Path $root -Parent) '.gradle-home'
if (Test-Path $gradleHome) { $env:GRADLE_USER_HOME = $gradleHome }

$jar = Join-Path $root 'hql-console-demo\build\libs\hql-console-demo.jar'
$log = Join-Path $env:TEMP "hql-console-demo-$Port.log"
$base = "http://localhost:$Port$ContextPath"
$script:ok = 0
$script:fail = 0

function Check([string]$name, [bool]$condition, [string]$detail = '') {
    if ($condition) {
        $script:ok++
        Write-Host ("  PASS  {0}" -f $name) -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host ("  FAIL  {0}   -> {1}" -f $name, $detail) -ForegroundColor Red
    }
}

function Exec([string]$hql) {
    $body = @{ hql = $hql } | ConvertTo-Json -Compress
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$base/hqlconsole/api/execute" `
                               -ContentType 'application/json' -Body $body -TimeoutSec 30
        return @{ status = $r.StatusCode; json = ($r.Content | ConvertFrom-Json); raw = $r.Content }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $text = $reader.ReadToEnd()
            $reader.Close()
            return @{ status = [int]$resp.StatusCode; json = ($text | ConvertFrom-Json); raw = $text }
        }
        return @{ status = 0; json = $null; raw = $_.Exception.Message }
    }
}

Write-Host '== Compilando ==' -ForegroundColor Cyan
& (Join-Path $root 'gradlew.bat') -p $root --console=plain -q build
if ($LASTEXITCODE -ne 0) { throw 'El build fallo.' }

# El workflow de release (que no se puede correr desde acá) le pregunta la version al build con
# printVersion y la compara con el tag. Si esa tarea dejara de imprimir la version, el release
# saldria con los jars titulados con otro numero: se comprueba contra el nombre del jar real.
$versionImpreso = (& (Join-Path $root 'gradlew.bat') -p $root --console=plain -q printVersion | Select-Object -Last 1)
$versionImpreso = "$versionImpreso".Trim()
$jarStarter = Get-ChildItem (Join-Path $root 'hql-console-starter\build\libs\hql-console-starter-*.jar') -ErrorAction SilentlyContinue |
              Where-Object { $_.Name -notmatch 'sources' } | Select-Object -First 1
Check 'printVersion coincide con el nombre del jar del starter' `
      ($null -ne $jarStarter -and $versionImpreso -ne '' -and $jarStarter.Name -eq "hql-console-starter-$versionImpreso.jar") `
      "printVersion=[$versionImpreso] jar=$($jarStarter.Name)"

Write-Host "== Levantando el demo en el puerto $Port (context-path '$ContextPath', tope $MaxRows) ==" -ForegroundColor Cyan
$javaArgs = @('-jar', "`"$jar`"", "--server.port=$Port", "--hql-console.max-rows=$MaxRows")
if ($ContextPath) { $javaArgs += "--server.servlet.context-path=$ContextPath" }
$proc = Start-Process -FilePath 'java' -ArgumentList $javaArgs -PassThru -WindowStyle Hidden `
                      -RedirectStandardOutput $log -RedirectStandardError "$log.err"

try {
    $page = $null
    for ($i = 0; $i -lt 90; $i++) {
        try { $page = Invoke-WebRequest -UseBasicParsing "$base/hqlconsole" -TimeoutSec 2; break }
        catch { Start-Sleep -Milliseconds 500 }
    }

    Write-Host ''
    if ($null -eq $page) {
        Write-Host "La app no respondio. Ultimas lineas de $log :" -ForegroundColor Yellow
        Get-Content $log -Tail 25 -ErrorAction SilentlyContinue
        throw 'La aplicacion no arranco.'
    }

    Check 'GET /hqlconsole responde 200 HTML' ($page.StatusCode -eq 200 -and "$($page.Headers['Content-Type'])" -like 'text/html*') $page.StatusCode
    Check 'la pagina trae el textarea y el boton' ($page.Content -match '<textarea' -and $page.Content -match 'id="run"')
    $barra = [regex]::Match($page.Content, '(?s)<div class="barra">(.*?)</div>')
    Check 'el encabezado solo dice HQL Console' ($barra.Success -and $barra.Groups[1].Value -match 'HQL Console' -and $barra.Groups[1].Value -notmatch '<span') 'el encabezado tiene algo demas'
    Check 'la pagina ya no muestra la ruta ni el aviso' ($page.Content -notmatch 'class="ruta"' -and $page.Content -notmatch 'herramienta de desarrollo') 'quedo la ruta o el aviso'
    Check 'la pagina apunta al base correcto' ($page.Content -match [regex]::Escape("const BASE = '$ContextPath/hqlconsole'")) "no encontro BASE = '$ContextPath/hqlconsole'"
    Check 'el banner avisa la URL en el log' ((Get-Content $log -Raw) -match 'Consola HQL en http') 'no aparece el banner'

    # --- consultas ---
    $r = Exec 'SELECT e.id, e.nombre FROM Empleado e'
    Check 'SELECT sin AS deduce los headers del select' (($r.json.headers -join ',') -eq 'id,nombre') ($r.json.headers -join ',')
    $esperadas = [Math]::Min($MaxRows, 6)
    Check "SELECT devuelve $esperadas fila(s)" ($r.json.rowCount -eq $esperadas) $r.json.rowCount
    Check 'la primera fila es Ana Gomez' ($r.json.rows[0][1] -eq 'Ana Gomez') ($r.json.rows[0] -join '|')

    $r = Exec 'SELECT e.id AS identificador, e.nombre FROM Empleado e'
    Check 'el alias explicito con AS gana' (($r.json.headers -join ',') -eq 'identificador,nombre') ($r.json.headers -join ',')

    $r = Exec 'from Empleado e'
    Check 'from Entidad aplana los atributos de Empleado' (($r.json.headers -join ',') -eq 'id,nombre,salario,ingreso,departamento') ($r.json.headers -join ',')
    Check 'la relacion sale como el id de la FK' ($r.json.rows[0][4] -eq 1) ($r.json.rows[0] -join '|')

    $r = Exec 'select e from Empleado e'
    Check 'con SELECT explicito la entidad sale como Tipo#id' ($r.json.rows[0][0] -eq 'Empleado#1') $r.json.rows[0][0]

    $r = Exec 'SELECT e.nombre, d FROM Empleado e LEFT JOIN e.departamento d'
    Check 'la asociacion LAZY se resuelve sin explotar' ($r.json.rowCount -eq [Math]::Min($MaxRows, 6)) $r.json.rowCount

    # Elena Paz es una de las que no tiene departamento: sirve para ver el NULL sin depender del tope.
    $r = Exec "SELECT e.nombre, d FROM Empleado e LEFT JOIN e.departamento d WHERE e.nombre = 'Elena Paz'"
    Check 'la asociacion nula no elimina la fila' ($r.json.rowCount -eq 1) $r.json.rowCount
    Check 'los NULL viajan como null' ($null -eq $r.json.rows[0][1]) ($r.json.rows[0][1])

    $r = Exec 'SELECT count(e) FROM Empleado e'
    Check 'los agregados conservan su header' (($r.json.headers -join ',') -eq 'count(e)') ($r.json.headers -join ',')
    Check 'count(e) da 6' ($r.json.rows[0][0] -eq 6) $r.json.rows[0][0]

    $r = Exec 'SELECT e.id, e.nombre FROM Empleado e WHERE e.id = 999'
    Check 'con 0 filas igual hay headers' (($r.json.headers -join ',') -eq 'id,nombre' -and $r.json.rowCount -eq 0) ($r.json.headers -join ',')

    $r = Exec 'SELECT e.nombr FROM Empleado e'
    Check 'el HQL invalido devuelve 400 con mensaje' ($r.status -eq 400 -and $r.json.error) "$($r.status) $($r.raw)"
    Check 'el error incluye la causa raiz' ($null -ne $r.json.cause) $r.raw

    $r = Exec 'SELECT e.id FROM Empleado e WHERE e.noexiste = 1'
    Check 'el atributo inexistente devuelve 400' ($r.status -eq 400 -and $r.json.error) $r.status

    # --- escrituras ---
    $r = Exec "UPDATE Empleado e SET e.salario = 777 WHERE e.nombre = 'Ana Gomez'"
    Check 'UPDATE informa filas afectadas' ($r.json.type -eq 'DML' -and $r.json.affectedRows -eq 1) $r.raw

    $r = Exec "SELECT e.salario FROM Empleado e WHERE e.nombre = 'Ana Gomez'"
    Check 'UPDATE persistio (commit)' ($r.json.rows[0][0] -eq 777) ($r.json.rows[0][0])

    $r = Exec "DELETE FROM Empleado e WHERE e.nombre = 'Fabio Luna'"
    Check 'DELETE informa filas afectadas' ($r.json.type -eq 'DML' -and $r.json.affectedRows -eq 1) $r.raw

    $r = Exec 'SELECT count(e) FROM Empleado e'
    Check 'DELETE persistio (quedan 5)' ($r.json.rows[0][0] -eq 5) $r.json.rows[0][0]

    # --- sentencias propias de la consola: DESC ---
    $r = Exec 'DESC Libro'
    Check 'DESC devuelve las 4 columnas del contrato' (($r.json.headers -join ',') -eq 'CAMPO,TIPO SQL,ATRIBUTO,TIPO JAVA') ($r.json.headers -join ',')
    Check 'DESC respeta el orden de declaracion de la entidad' ($r.json.rows[0][0] -eq 'ID' -and $r.json.rows[1][0] -eq 'TITULO') "$($r.json.rows[0][0]),$($r.json.rows[1][0])"
    $fk = $r.json.rows | Where-Object { $_[2] -eq 'autor' }
    Check 'DESC muestra la FK como CAMPO y la relacion como ATRIBUTO' ($fk[0] -match 'ID_AUTOR' -and $fk[3] -eq 'Autor') ($fk -join ' | ')
    $fecha = $r.json.rows | Where-Object { $_[2] -eq 'fechaPublicacion' }
    Check 'DESC trae el TIPO SQL real de la base' ($fecha[1] -eq 'DATE' -and $fecha[3] -eq 'LocalDate') ($fecha -join ' | ')
    $titulo = $r.json.rows | Where-Object { $_[2] -eq 'titulo' }
    Check 'DESC trae el TIPO SQL de un texto' ($titulo[1] -match 'CHAR|VARCHAR|TEXT') $titulo[1]
    # La columna ATRIBUTO de DESC es el contrato de titulos de "from <Entidad>".
    $atributosDesc = ($r.json.rows | ForEach-Object { $_[2] }) -join ','

    $r = Exec 'DESC'
    Check 'DESC sin argumentos lista las entidades' (($r.json.headers -join ',') -eq 'ENTIDAD,TABLA,CAMPOS' -and $r.json.rowCount -ge 4) $r.raw
    Check 'la lista incluye Libro con su tabla' (@($r.json.rows | Where-Object { $_[0] -eq 'Libro' -and $_[1] -eq 'libros' }).Count -eq 1) $r.raw
    $r = Exec 'DESC NoExiste'
    Check 'DESC de una entidad inexistente da 400 y lista las que hay' ($r.status -eq 400 -and $r.json.error -match 'Las que hay son') $r.raw

    # --- UPDATE sin WHERE: toca todo, o hasta el tope avisando (el seed tiene 6 libros) ---
    $r = Exec 'UPDATE Libro li SET li.disponible=false'
    $esperadas = [Math]::Min($MaxRows, 6)
    Check "UPDATE sin WHERE afecta $esperadas fila(s)" ($r.json.type -eq 'DML' -and $r.json.affectedRows -eq $esperadas) $r.raw
    if ($MaxRows -lt 6) {
        Check 'UPDATE avisa cuando el tope lo trunco' ($r.json.message -match 'tope') $r.json.message
    }

    # --- INSERT ---
    $r = Exec "INSERT INTO Libro li VALUES li.titulo='Las mil y una noches', li.fechaPublicacion='1994-11-23', li.fechaAlta=NOW, li.genero='ENSAYO'"
    Check 'INSERT devuelve el id generado' ($r.json.type -eq 'DML' -and $r.json.message -match 'Libro#\d+') $r.raw

    $r = Exec "SELECT l.titulo, l.fechaPublicacion, l.fechaAlta, l.genero, l.precio FROM Libro l WHERE l.titulo = 'Las mil y una noches'"
    $nuevo = $r.json.rows[0]
    Check 'INSERT convirtio el texto a DATE' ($nuevo[1] -eq '1994-11-23') $nuevo[1]
    Check 'INSERT resolvio NOW a TIMESTAMP' ($nuevo[2] -match '^\d{4}-\d{2}-\d{2}T') $nuevo[2]
    Check 'INSERT convirtio el texto al enum (atributo sin setter)' ($nuevo[3] -eq 'ENSAYO') $nuevo[3]
    Check 'los campos omitidos quedaron en NULL' ($null -eq $nuevo[4]) $nuevo[4]

    $r = Exec "INSERT INTO Libro li VALUES li.titulo='Con autor', li.autor=1"
    $r = Exec "SELECT l.autor FROM Libro l WHERE l.titulo = 'Con autor'"
    Check 'INSERT resuelve la relacion por id (li.autor=1)' ($r.json.rows[0][0] -eq 'Autor#1') $r.json.rows[0][0]

    $r = Exec "INSERT INTO Libro li VALUES li.titulo='Autor por ruta', li.autor.id=2"
    $r = Exec "SELECT l.autor FROM Libro l WHERE l.titulo = 'Autor por ruta'"
    Check 'INSERT acepta tambien la ruta li.autor.id=2' ($r.json.rows[0][0] -eq 'Autor#2') $r.json.rows[0][0]

    $r = Exec "INSERT INTO Libro li VALUES li.titulo='Hoy', li.fechaPublicacion=NOW"
    $r = Exec "SELECT l.id, l.fechaPublicacion FROM Libro l WHERE l.titulo = 'Hoy'"
    Check 'NOW en un campo DATE no lleva hora' ($r.json.rows[0][1] -match '^\d{4}-\d{2}-\d{2}$') $r.json.rows[0][1]
    $idHoy = $r.json.rows[0][0]

    $r = Exec 'INSERT INTO Libro li VALUES li.precio=100'
    Check 'INSERT que omite un NOT NULL falla con el error de la base' ($r.status -eq 400 -and $r.json.error -match 'null') $r.raw

    $r = Exec "INSERT INTO Libro li VALUES li.inventado='x'"
    Check 'INSERT con un campo inexistente lista los que hay' ($r.status -eq 400 -and $r.json.error -match 'no tiene el campo') $r.raw

    # --- UPDATE ---
    $r = Exec "UPDATE Libro li SET li.titulo='Las 1000 y dos noches', li.fechaModif=NOW WHERE li.id=$idHoy"
    Check 'UPDATE informa las filas afectadas' ($r.json.type -eq 'DML' -and $r.json.affectedRows -eq 1) $r.raw

    $r = Exec "SELECT l.titulo, l.precio, l.fechaModif FROM Libro l WHERE l.id=$idHoy"
    Check 'UPDATE aplico el SET' ($r.json.rows[0][0] -eq 'Las 1000 y dos noches') $r.json.rows[0][0]
    Check 'UPDATE resolvio NOW dentro del SET' ($r.json.rows[0][2] -match '^\d{4}-\d{2}-\d{2}T') $r.json.rows[0][2]
    Check 'UPDATE no toco los campos fuera del SET' ($null -eq $r.json.rows[0][1]) $r.json.rows[0][1]

    $r = Exec 'UPDATE Libro li SET li.disponible=true WHERE li.autor IS NOT NULL AND li.precio > 15000'
    Check 'UPDATE acepta un WHERE compuesto (lo parsea Hibernate)' ($r.json.affectedRows -eq 2) $r.raw

    $r = Exec "UPDATE Libro SET titulo='Sin alias' WHERE id=2"
    Check 'UPDATE sin alias tambien funciona' ($r.json.affectedRows -eq 1) $r.raw

    $r = Exec "UPDATE Libro li SET li.nope=1 WHERE li.id=1"
    Check 'UPDATE con campo inexistente da un error claro' ($r.status -eq 400 -and $r.json.error -match 'no tiene el campo') $r.raw

    $r = Exec "UPDATE Libro li SET li.fechaPublicacion='no es fecha' WHERE li.id=1"
    Check 'UPDATE con literal invalido explica la conversion' ($r.status -eq 400 -and $r.json.error -match 'No pude asignar') $r.raw

    $r = Exec 'INSERT INTO Libro (titulo) SELECT e.nombre FROM Empleado e WHERE e.id = 1'
    Check 'el INSERT ... SELECT de HQL real sigue funcionando (fallback)' ($r.json.type -eq 'DML' -and $r.json.affectedRows -eq 1) $r.raw

    # --- "from <Entidad>" sin SELECT: columnas planas ---
    $r = Exec 'SELECT count(l) FROM Libro l'
    $libros = $r.json.rows[0][0]
    $esperadas = [Math]::Min($MaxRows, $libros)

    $r = Exec 'from Libro'
    Check 'from <Entidad> aplana y titula con los ATRIBUTOS de DESC' (($r.json.headers -join ',') -eq $atributosDesc) "$($r.json.headers -join ',') vs $atributosDesc"
    Check "from <Entidad> trae $esperadas fila(s)" ($r.json.rowCount -eq $esperadas) $r.json.rowCount
    $posAutor = [array]::IndexOf($r.json.headers, 'autor')
    Check 'la relacion se muestra como el id de la FK' ($r.json.rows[0][$posAutor] -eq 1) $r.json.rows[0][$posAutor]

    $r = Exec "from Libro l where l.genero = 'NOVELA' order by l.id desc"
    Check 'from <Entidad> con WHERE y ORDER BY sigue aplanado' (($r.json.headers -join ',') -eq $atributosDesc) ($r.json.headers -join ',')

    $r = Exec 'from Libro l join fetch l.autor'
    Check 'join fetch tambien se aplana (la fila es solo la entidad)' ($r.json.headers.Count -eq 9) $r.json.headers.Count

    $r = Exec 'select l from Libro l'
    Check 'con SELECT explicito NO se aplana' ($r.json.headers.Count -eq 1 -and $r.json.rows[0][0] -eq 'Libro#1') $r.json.rows[0][0]

    $r = Exec 'from Libro l join l.autor a'
    Check 'un join explicito devuelve las dos raices' ($r.json.headers.Count -eq 2) ($r.json.headers -join ',')

    # --- entidades y atributos son case sensitive ---
    $r = Exec 'DESC libro'
    Check 'DESC con la entidad en minuscula falla y sugiere' ($r.status -eq 400 -and $r.json.error -match "Quisiste decir 'Libro'") $r.raw
    $r = Exec 'from libro'
    Check 'from con la entidad en minuscula falla y sugiere' ($r.status -eq 400 -and $r.json.error -match "Quisiste decir 'Libro'") $r.raw
    $r = Exec 'SELECT l.tiTulo FROM Libro l'
    Check 'un atributo mal capitalizado falla' ($r.status -eq 400) $r.raw
    $r = Exec "INSERT INTO Libro li VALUES li.TITULO='x'"
    Check 'INSERT con el atributo mal capitalizado sugiere el correcto' ($r.status -eq 400 -and $r.json.error -match "Quisiste decir 'titulo'") $r.raw
    $r = Exec "UPDATE Libro li SET LI.titulo='x' WHERE li.id=1"
    # El patron va sin tildes a proposito: PowerShell 5.1 lee este archivo como ANSI si no tiene BOM.
    Check 'el alias mal capitalizado se avisa' ($r.status -eq 400 -and $r.json.error -match 'y lo escribiste') $r.raw
    $r = Exec 'SELECT l.titulo FROM Libro l'
    Check 'y con el case correcto funciona' ($r.status -eq 200 -and $r.json.rowCount -ge 1) $r.status

    # --- que se ejecuta: la seleccion, o el parrafo del cursor ---
    Check 'la pagina trae el ejecutar-por-seleccion' ($page.Content -match 'id="seleccion"' -and $page.Content -match 'ta\.selectionStart' -and $page.Content -match 'function textoAejecutar') 'falta el codigo de seleccion'
    Check 'el boton no le roba la seleccion al textarea' ($page.Content -match "btn\.addEventListener\('mousedown'") 'falta el preventDefault del mousedown'
    Check 'la pagina trae el ejecutar-por-parrafo' ($page.Content -match 'function rangoParrafo' -and $page.Content -match 'function lineasDe' -and $page.Content -match 'INICIO funciones puras') 'falta el calculo del parrafo'
    Check 'una seleccion en blanco cae en el parrafo' ($page.Content -match 'recorte\.trim\(\)') 'no esta el fallback de seleccion vacia'

    # --- el boton, dentro del panel izquierdo y debajo del textarea ---
    $panelIzq = [regex]::Match($page.Content, '(?s)id="panel-editor"(.*?)id="divisor"')
    Check 'el boton esta dentro del panel izquierdo' ($panelIzq.Success -and $panelIzq.Groups[1].Value -match 'id="run"') 'el boton no esta en el panel izquierdo'
    Check 'el boton va despues del textarea' ($panelIzq.Success -and $panelIzq.Groups[1].Value -match '(?s)<textarea.*id="run"') 'el boton no esta debajo del textarea'
    Check 'el boton se alinea a la derecha' ($page.Content -match '\.pie-editor #run' -and $page.Content -match 'margin-left:auto') 'no esta el margin-left:auto'
    Check 'el alcance se avisa al pie del editor' ($page.Content -match 'id="pista"' -and $page.Content -match 'seleccion\.textContent') 'no esta el aviso de alcance'

    if (Get-Command node -ErrorAction SilentlyContinue) {
        # Las funciones puras se extraen de la pagina que sirve el jar y se corren de verdad, en Node.
        # El marcador se consume entero (hasta el fin de linea) para que el bloque no arranque a
        # mitad del comentario.
        $bloque = [regex]::Match($page.Content, '(?s)// INICIO funciones puras[^\r\n]*(.*?)// FIN funciones puras')
        if ($bloque.Success) {
            $js = $bloque.Groups[1].Value + @'

function check(n, o, e) { if (o === e) { console.log('PASS ' + n); } else { console.log('FAIL ' + n + ' -> [' + o + '] esperado [' + e + ']'); process.exitCode = 1; } }

// --- la seleccion ---
var t = 'SELECT 1\nfrom A';
check('sin seleccion textoAejecutar devuelve todo', textoAejecutar(t, 0, 0), t);
check('con seleccion devuelve solo eso', textoAejecutar(t, 0, 8), 'SELECT 1');
check('seleccion invertida devuelve todo', textoAejecutar(t, 5, 2), t);

// --- el parrafo del cursor ---
function par(texto, pos) { var r = rangoParrafo(texto, pos); return texto.substring(r.inicio, r.fin); }
var doc = ['-- uno', 'SELECT 1', '', '-- dos', 'SELECT 2', '', '', '-- tres', 'SELECT 3'].join('\n');
check('parrafo: cursor en el primero', par(doc, doc.indexOf('SELECT 1') + 2), '-- uno\nSELECT 1');
check('parrafo: cursor en el primero por su nombre', par(doc, doc.indexOf('-- uno')), '-- uno\nSELECT 1');
check('parrafo: cursor en el segundo', par(doc, doc.indexOf('SELECT 2') + 2), '-- dos\nSELECT 2');
check('parrafo: cursor al final de una linea no se come la de abajo', par(doc, doc.indexOf('SELECT 2') + 8), '-- dos\nSELECT 2');
check('parrafo: cursor en la linea en blanco ancla al de arriba', par(doc, doc.indexOf('SELECT 2') + 9), '-- dos\nSELECT 2');
check('parrafo: cursor en el tercero', par(doc, doc.indexOf('SELECT 3') + 4), '-- tres\nSELECT 3');
check('parrafo: cursor al final del texto', par(doc, doc.length), '-- tres\nSELECT 3');

var soloUno = 'SELECT 1\nSELECT 2\nSELECT 3';
check('parrafo: sin lineas en blanco todo el texto es un parrafo', par(soloUno, 12), soloUno);
check('parrafo: recorta las lineas en blanco de los bordes', par('\n\nSELECT 1\n\n\n', 4), 'SELECT 1');
check('parrafo: linea en blanco al inicio ancla hacia abajo', par('\n\nSELECT 9', 0), 'SELECT 9');
check('parrafo: un texto solo de blancos no tiene parrafo', par('   \n\n', 2).trim(), '');
check('parrafo: con CRLF el de abajo no se mezcla', par('A\r\n\r\nB', 6), 'B');
'@
            $archivo = Join-Path $env:TEMP 'hql-console-sel-test.js'
            Set-Content -Path $archivo -Value $js -Encoding UTF8
            # Con ErrorActionPreference='Stop', un node que escribe en stderr aborta el script entero
            # y el FAIL se pierde: acá interesa el codigo de salida, no la excepcion de PowerShell.
            $previo = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            $salida = & node $archivo 2>&1
            $codigo = $LASTEXITCODE
            $ErrorActionPreference = $previo
            Check 'seleccion y parrafos, en Node sobre la pagina servida' ($codigo -eq 0) ($salida -join ' | ')
        } else {
            Check 'seleccion y parrafos, en Node sobre la pagina servida' $false 'no encontre el bloque de funciones puras'
        }
    }

    # --- layout partido con divisor movible ---
    Check 'la pagina trae el layout partido' ($page.Content -match 'id="panel-editor"' -and $page.Content -match 'id="panel-resultado"') 'faltan los paneles'
    Check 'la separacion es vertical y movible' ($page.Content -match 'id="divisor"' -and $page.Content -match 'cursor:col-resize' -and $page.Content -match 'pointerdown') 'falta el divisor arrastrable'
    Check 'el ancho del editor es configurable por CSS' ($page.Content -match '--ancho-editor' -and $page.Content -match 'flex:0 0 var\(--ancho-editor\)') 'no esta el ancho variable'
    Check 'los resultados viven en el panel derecho' (([regex]::Match($page.Content, '(?s)id="panel-resultado".*?id="crudo"')).Success -and $page.Content -match 'id="vacio"') 'los resultados no estan en el panel derecho'

    # --- persistencia del texto ---
    Check 'el texto del editor se persiste en el navegador' ($page.Content -match "CLAVE_TEXTO = 'hql-console\.consulta'" -and $page.Content -match 'ALMACEN\.setItem\(CLAVE_TEXTO') 'no se guarda el texto'
    Check 'el texto se restituye al abrir la pagina' ($page.Content -match 'ta\.value = \(textoGuardado === null') 'no se restituye el texto'
    Check 'se guarda tambien mientras se escribe' ($page.Content -match "ta\.addEventListener\('input'" -and $page.Content -match 'setTimeout\(guardarTexto') 'no hay guardado al tipear'
    Check 'el ancho del divisor tambien se persiste' ($page.Content -match 'CLAVE_ANCHO') 'no se persiste el ancho'

    # --- nada de cache: si el navegador guarda la pagina, los cambios no se ven ---
    Check 'la pagina se sirve sin cache' ("$($page.Headers['Cache-Control'])" -match 'no-store') "Cache-Control: $($page.Headers['Cache-Control'])"

    # --- tope de filas ---
    if ($MaxRows -lt 6) {
        $r = Exec 'SELECT e.id FROM Empleado e'
        Check 'el tope de filas trunca el resultado' ($r.json.truncated -eq $true -and $r.json.rowCount -eq $MaxRows) "truncated=$($r.json.truncated) rowCount=$($r.json.rowCount)"
    }

    # --- context-path ---
    if ($ContextPath) {
        $sinContexto = 0
        try { Invoke-WebRequest -UseBasicParsing "http://localhost:$Port/hqlconsole" -TimeoutSec 5 | Out-Null; $sinContexto = 200 }
        catch { $sinContexto = [int]$_.Exception.Response.StatusCode }
        Check 'la ruta sin context-path da 404' ($sinContexto -eq 404) $sinContexto
    }
}
finally {
    if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
    Start-Sleep -Milliseconds 500
    Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
}

Write-Host ''
Write-Host ("== {0} PASS / {1} FAIL ==" -f $script:ok, $script:fail) -ForegroundColor ($(if ($script:fail -eq 0) { 'Green' } else { 'Red' }))
if ($script:fail -gt 0) { exit 1 }
