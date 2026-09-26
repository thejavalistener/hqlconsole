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

function Exec([string]$hql, $DryRun = $null, [string]$Language = 'hql') {
	$payload = @{ hql = $hql }
	if ($null -ne $DryRun) { $payload['dryRun'] = $DryRun }
	$payload['language'] = $Language
    $body = $payload | ConvertTo-Json -Compress
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
#
# Se busca el jar de ESA version, no el primero que aparezca en build/libs: despues de un par de
# releases hay jars de versiones viejas y agarrar el primero daba un FAIL falso.
$versionImpreso = (& (Join-Path $root 'gradlew.bat') -p $root --console=plain -q printVersion | Where-Object { $_ -match '^\d+\.\d+\.\d+$' } | Select-Object -Last 1)
$versionImpreso = "$versionImpreso".Trim()
$jarEsperado = Join-Path $root "hql-console-starter\build\libs\hql-console-starter-$versionImpreso.jar"
$jarStarter = Get-Item $jarEsperado -ErrorAction SilentlyContinue
Check 'printVersion coincide con el nombre del jar del starter' `
      ($null -ne $jarStarter -and $versionImpreso -ne '') `
      "printVersion=[$versionImpreso] esperaba $jarEsperado"

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
    Check 'la pagina trae el textarea sin boton Ejecutar' ($page.Content -match '<textarea' -and $page.Content -notmatch 'id="run"')
    Check 'la pagina trae las dos solapas y los dos editores' ($page.Content -match 'id="tab-hql"' -and $page.Content -match 'id="tab-sql"' -and $page.Content -match 'id="sql"') 'falta HQL o SQL'
    Check 'la pagina persiste SQL y la solapa activa' ($page.Content -match 'hql-console.consulta-sql' -and $page.Content -match 'hql-console.solapa') 'faltan claves SQL'
    $barra = [regex]::Match($page.Content, '(?s)<div class="barra">(.*?)</div>')
    Check 'el encabezado trae consola, idioma y atajo' ($barra.Success -and $barra.Groups[1].Value -match 'Consola' -and $barra.Groups[1].Value -match 'id="idioma-titulo"' -and $barra.Groups[1].Value -match 'id="atajo-ejecutar"') 'faltan elementos del encabezado'
    Check 'la pagina ya no muestra la ruta ni el aviso' ($page.Content -notmatch 'class="ruta"' -and $page.Content -notmatch 'herramienta de desarrollo') 'quedo la ruta o el aviso'
    $configEsperada = "{`"base`":`"$ContextPath/hqlconsole`",`"maxRows`":$MaxRows}"
    Check 'la pagina inyecta la configuracion correcta' `
          ($page.Content -match [regex]::Escape($configEsperada) -and $page.Content -match [regex]::Escape('const BASE = CONFIG.base;')) `
          "no encontro la configuracion $configEsperada"
    # Ojo: (Get-Content -Raw) puede devolver un array si el archivo tiene una sola linea, y entonces
    # -match devuelve un array y Check explota. El cast a [bool] lo deja siempre booleano.
    Check 'el banner avisa la URL en el log' ([bool]((Get-Content $log -Raw) -match 'Consola HQL en http')) 'no aparece el banner'

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
    Check 'la consola carga la relacion etiquetable sin exigir join fetch' `
          ($r.json.rows[0][4] -eq '1 (Sistemas)') ($r.json.rows[0] -join '|')

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
    # Orden del contrato: primero lo que uno escribe (ATRIBUTO, TIPO JAVA) y después lo que hay en la
    # base (CAMPO, TIPO SQL).
    $r = Exec 'DESC Libro'
    Check 'DESC devuelve las 4 columnas del contrato' (($r.json.headers -join ',') -eq 'ATRIBUTO,TIPO JAVA,CAMPO,TIPO SQL') ($r.json.headers -join ',')
    Check 'DESC muestra primero el ATRIBUTO y despues el CAMPO' ($r.json.rows[0][0] -eq 'id*' -and $r.json.rows[0][2] -eq 'ID') "$($r.json.rows[0][0]),$($r.json.rows[0][2])"
    Check 'DESC marca con * el atributo que es @Id' ($r.json.rows[0][0] -eq 'id*') $r.json.rows[0][0]
    # El * va SOLO en el id: los demas atributos van pelados.
    Check 'ningun otro atributo lleva el *' (@($r.json.rows | Where-Object { $_[0] -like '*`*' -and $_[0] -ne 'id*' }).Count -eq 0) (($r.json.rows | ForEach-Object { $_[0] }) -join ',')
    $fk = $r.json.rows | Where-Object { $_[0] -eq 'autor' }
    Check 'DESC respeta el orden de declaracion de la entidad' ($r.json.rows[0][2] -eq 'ID' -and $r.json.rows[1][2] -eq 'TITULO') "$($r.json.rows[0][2]),$($r.json.rows[1][2])"
    Check 'DESC muestra la FK como CAMPO y la relacion como TIPO JAVA' ($fk[2] -match 'ID_AUTOR' -and $fk[1] -eq 'Autor') ($fk -join ' | ')
    $fecha = $r.json.rows | Where-Object { $_[0] -eq 'fechaPublicacion' }
    Check 'DESC trae el TIPO SQL real de la base' ($fecha[3] -eq 'DATE' -and $fecha[1] -eq 'LocalDate') ($fecha -join ' | ')
    $titulo = $r.json.rows | Where-Object { $_[0] -eq 'titulo' }
    Check 'DESC trae el TIPO SQL de un texto' ($titulo[3] -match 'CHAR|VARCHAR|TEXT') $titulo[3]
    # La columna ATRIBUTO de DESC es el contrato de titulos de "from <Entidad>", pero el * de la marca
    # de id NO viaja: los titulos de la grilla son los nombres de atributo pelados.
    $atributosDesc = ($r.json.rows | ForEach-Object { $_[0] -replace '\*$','' }) -join ','

    $r = Exec 'DESC'
    Check 'DESC sin argumentos lista las entidades' (($r.json.headers -join ',') -eq 'ENTIDAD,TABLA,CAMPOS' -and $r.json.rowCount -ge 4) $r.raw    Check 'la lista incluye Libro con su tabla' (@($r.json.rows | Where-Object { $_[0] -eq 'Libro' -and $_[1] -eq 'LIBROS' }).Count -eq 1) $r.raw
    # Convencion de la consola: el nombre fisico en un solo caso se muestra en MAYUSCULAS...
    Check 'la tabla en un solo caso se muestra en mayusculas' (@($r.json.rows | Where-Object { $_[0] -eq 'Libro' -and $_[1] -ceq 'LIBROS' }).Count -eq 1) $r.raw
    # ...y el que tiene mayusculas mezcladas (o sea, entrecomillado en SQL) se muestra tal cual.
    Check 'la tabla con mayusculas mezcladas se muestra tal cual' (@($r.json.rows | Where-Object { $_[0] -eq 'Etiqueta' -and $_[1] -ceq 'EtiquetaRara' }).Count -eq 1) $r.raw
    # Los atributos y las clases no se tocan: se muestran como estan escritos.
    Check 'la entidad se muestra como la clase' (@($r.json.rows | Where-Object { $_[0] -ceq 'Etiqueta' }).Count -eq 1) $r.raw

    # --- SQL nativo de solo lectura ---
    $r = Exec 'SELECT ID, TITULO FROM LIBROS ORDER BY ID' $null 'sql'
    Check 'SQL SELECT nativo devuelve filas y headers' ($r.status -eq 200 -and $r.json.rowCount -gt 0 -and ($r.json.headers -join ',') -eq 'ID,TITULO') $r.raw
    if ($MaxRows -lt 6) { Check 'SQL respeta el tope de filas' ($r.json.rowCount -eq $MaxRows -and $r.json.truncated -eq $true) $r.raw }
    $r = Exec "-- comentario`nSELECT ID FROM LIBROS" $null 'sql'
    Check 'SQL acepta comentarios' ($r.status -eq 200 -and $r.json.rowCount -gt 0) $r.raw
    $r = Exec 'DROP TABLE LIBROS' $null 'sql'
    Check 'SQL bloquea DDL y dice que solo permite SELECT' ($r.status -eq 400 -and $r.json.error -match 'permite SELECT') $r.raw
    $r = Exec 'SELECT ID FROM LIBROS; SELECT TITULO FROM LIBROS' $null 'sql'
    Check 'SQL bloquea varias sentencias' ($r.status -eq 400 -and $r.json.error -match 'una sentencia por vez') $r.raw
    $r = Exec 'DESC' $null 'sql'
    Check 'DESC SQL lista tablas sin catalogos del sistema' ($r.status -eq 200 -and ($r.json.headers -join ',') -eq 'TABLA,TIPO,ES_ENTIDAD' -and ($r.json.rows -join ',') -notmatch 'INFORMATION_SCHEMA') $r.raw
    $r = Exec 'DESC LIBROS' $null 'sql'
    Check 'DESC SQL marca PK y muestra el destino de FK' ($r.status -eq 200 -and ($r.json.headers -join ',') -eq 'CAMPO,TIPO SQL,RELACION' -and @($r.json.rows | Where-Object { $_[0] -match 'ID \(PK\)' }).Count -gt 0 -and @($r.json.rows | Where-Object { $_[0] -match '\(FK\)$' -and $_[2] -match 'AUTORES \(ID\)' }).Count -gt 0) $r.raw
    Check 'DESC SQL muestra primero las columnas PK' ($r.json.rows.Count -gt 0 -and $r.json.rows[0][0] -match '\(PK\)') ($r.json.rows[0] -join ' | ')
    $r = Exec 'DESC NoExiste'
    Check 'DESC HQL de una entidad inexistente da 400' ($r.status -eq 400 -and $r.json.error -eq 'Entidad no encontrada: NoExiste') $r.raw
    $r = Exec 'DESC NO_EXISTE' $null 'sql'
    Check 'DESC SQL de una tabla inexistente da 400' ($r.status -eq 400 -and $r.json.error -eq "No conozco la tabla 'NO_EXISTE'.") $r.raw

    # --- UPDATE sin WHERE: toca todo, o hasta el tope avisando (el seed tiene 6 libros) ---
    $r = Exec 'UPDATE Libro li SET li.disponible=false'
    $esperadas = [Math]::Min($MaxRows, 6)
    Check "UPDATE sin WHERE afecta $esperadas fila(s)" ($r.json.type -eq 'DML' -and $r.json.affectedRows -eq $esperadas) $r.raw
    if ($MaxRows -lt 6) {
        Check 'UPDATE avisa cuando el tope lo trunco' ($r.json.truncated -eq $true -and $r.json.message -match 'tope') "truncated=$($r.json.truncated) $($r.json.message)"
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

    # --- INSERT sin alias: el campo va pelado, sin el "li." adelante (formato 4.2) ---
    $r = Exec "INSERT INTO Libro VALUES titulo='Sin alias', precio=12345, genero='ENSAYO'"
    Check 'INSERT sin alias (VALUES campo=valor) funciona' ($r.json.type -eq 'DML' -and $r.json.affectedRows -eq 1) $r.raw
    $r = Exec "SELECT l.titulo, l.precio, l.genero FROM Libro l WHERE l.titulo = 'Sin alias'"
    Check 'el INSERT sin alias convirtio y guardo los valores' `
          ($r.json.rowCount -eq 1 -and $r.json.rows[0][1] -eq 12345 -and $r.json.rows[0][2] -eq 'ENSAYO') ($r.json.rows[0] -join '|')
    $r = Exec "INSERT INTO Libro VALUES inventado='x'"
    Check 'el INSERT sin alias tambien avisa del campo inexistente' ($r.status -eq 400 -and $r.json.error -match 'no tiene el campo') $r.raw

    # --- INSERT estilo SQL clasico: (columnas) VALUES (valores), por posicion ---
    $r = Exec "INSERT INTO Libro (titulo, fechaPublicacion, fechaAlta, genero, precio) VALUES ('Posicional', '1999-12-31', NOW, 'NOVELA', 4321)"
    Check 'INSERT posicional (columnas) VALUES (valores) funciona' ($r.json.type -eq 'DML' -and $r.json.affectedRows -eq 1) $r.raw
    $r = Exec "SELECT l.titulo, l.fechaPublicacion, l.genero, l.precio FROM Libro l WHERE l.titulo = 'Posicional'"
    Check 'el INSERT posicional convierte los tipos por posicion' `
          ($r.json.rowCount -eq 1 -and $r.json.rows[0][1] -eq '1999-12-31' -and $r.json.rows[0][2] -eq 'NOVELA' -and $r.json.rows[0][3] -eq 4321) ($r.json.rows[0] -join '|')

    $r = Exec "INSERT INTO Libro (titulo, precio) VALUES ('Dos valores', 1, 2)"
    Check 'INSERT posicional avisa si no coinciden columnas y valores' ($r.status -eq 400 -and $r.json.error -match '2 columna.*3 valor') $r.raw

    # --- INSERT posicional con relaciones: el valor es el id de la entidad referenciada ---
    $r = Exec "INSERT INTO Libro (titulo, autor) VALUES ('Posicional con autor', 1)"
    Check 'INSERT posicional acepta el id de una relacion' ($r.json.type -eq 'DML' -and $r.json.affectedRows -eq 1) $r.raw
    $r = Exec "SELECT l.titulo, l.autor FROM Libro l WHERE l.titulo = 'Posicional con autor'"
    Check 'el id de la relacion quedo guardado como la entidad' `
          ($r.json.rowCount -eq 1 -and $r.json.rows[0][1] -eq 'Autor#1') ($r.json.rows[0] -join '|')

    $r = Exec "INSERT INTO Empleado (nombre, salario, departamento) VALUES ('Nuevo Empleado', 100, 1)"
    Check 'INSERT posicional con dos columnas, una de ellas relacion, funciona' ($r.json.type -eq 'DML') $r.raw
    $r = Exec "SELECT e.nombre, e.salario, e.departamento FROM Empleado e WHERE e.nombre = 'Nuevo Empleado'"
    Check 'la relacion por id se resolvio tambien en Empleado' `
          ($r.json.rowCount -eq 1 -and $r.json.rows[0][2] -eq 'Departamento#1') ($r.json.rows[0] -join '|')

    # El multi-fila y el alias con columnas NO son gramatica de la consola, pero Hibernate los
    # entiende como HQL y los ejecuta: por eso el parser devuelve null y sigue por ese camino.
    # Ojo: ese camino es un bulk de HQL (sin @PrePersist ni validacion) y no conoce NOW.
    $r = Exec "INSERT INTO Libro (titulo, precio) VALUES ('Multi A', 11), ('Multi B', 22)"
    Check 'el multi-fila lo resuelve Hibernate por el camino HQL' ($r.json.type -eq 'DML' -and $r.json.affectedRows -eq 2) $r.raw
    $r = Exec "SELECT count(l) FROM Libro l WHERE l.titulo LIKE 'Multi %'"
    Check 'el multi-fila inserto las dos filas' ($r.json.rows[0][0] -eq 2) $r.raw

    $r = Exec "INSERT INTO Libro li (titulo) VALUES ('Con alias y columnas')"
    Check 'el INSERT con alias y columnas tambien lo resuelve Hibernate' ($r.json.type -eq 'DML' -and $r.json.affectedRows -eq 1) $r.raw

    $r = Exec "INSERT INTO Libro (titulo, inventado) VALUES ('x', 1)"
    Check 'INSERT posicional con una columna inexistente avisa' ($r.status -eq 400 -and $r.json.error -match 'no tiene el campo') $r.raw

    $r = Exec "INSERT INTO Libro (titulo) VALUES ('Sin cerrar'"
    Check 'INSERT posicional sin cerrar el parentesis avisa' ($r.status -eq 400 -and $r.json.error -match 'no cierra') $r.raw

    # --- lote de INSERT separados por punto y coma (una sola transaccion) ---
    $r = Exec "INSERT INTO Libro (titulo, precio) VALUES ('Lote 1', 1); INSERT INTO Libro VALUES titulo='Lote 2', precio=2; INSERT INTO Libro li VALUES li.titulo='Lote 3', li.precio=3"
    Check 'el lote devuelve BATCH con las filas y las sentencias' `
          ($r.json.type -eq 'BATCH' -and $r.json.affectedRows -eq 3 -and $r.json.statementCount -eq 3) $r.raw
    $r = Exec "SELECT count(l) FROM Libro l WHERE l.titulo LIKE 'Lote %'"
    Check 'el lote inserto las tres filas (los tres formatos adentro)' ($r.json.rows[0][0] -eq 3) $r.raw

    $r = Exec "INSERT INTO Libro li VALUES li.titulo='Lote 4';; INSERT INTO Libro VALUES titulo='Lote 5';"
    Check 'el lote tolera el ; final y las sentencias vacias' `
          ($r.json.type -eq 'BATCH' -and $r.json.affectedRows -eq 2 -and $r.json.statementCount -eq 2) $r.raw

    $r = Exec "INSERT INTO Libro (titulo) VALUES ('Atomico 1'); INSERT INTO Libro (precio) VALUES (1); INSERT INTO Libro (titulo) VALUES ('Atomico 3')"
    Check 'un lote que falla en el medio devuelve 400' ($r.status -eq 400) $r.raw
    Check 'el error del lote dice que sentencia fallo' ($r.json.error -match 'sentencia 2 de 3') $r.raw
    Check 'el error del lote explica la causa de la base' ($r.json.cause -match 'null') $r.raw
    $r = Exec "SELECT count(l) FROM Libro l WHERE l.titulo LIKE 'Atomico %'"
    Check 'el lote que falla no dejo nada (una sola transaccion)' ($r.json.rows[0][0] -eq 0) $r.raw

    $r = Exec "INSERT INTO Libro (titulo) VALUES ('Lote 6'); SELECT count(l) FROM Libro l"
    Check 'un lote con SELECT avisa la posicion y lo rechaza' ($r.status -eq 400 -and $r.json.error -match 'sentencia 2 de 2 no es INSERT, UPDATE ni DELETE') $r.raw
    $r = Exec "SELECT count(l) FROM Libro l WHERE l.titulo = 'Lote 6'"
    Check 'el lote rechazado no inserto nada' ($r.json.rows[0][0] -eq 0) $r.raw

    # El ; dentro de un literal no parte la sentencia...
    $r = Exec "INSERT INTO Libro li VALUES li.titulo='Con ; adentro', li.precio=9"
    Check 'el ; dentro de un literal no parte la sentencia' ($r.json.type -eq 'DML' -and $r.json.affectedRows -eq 1) $r.raw
    $r = Exec "SELECT l.titulo FROM Libro l WHERE l.titulo = 'Con ; adentro'"
    Check 'el texto con ; adentro se guardo entero' ($r.json.rowCount -eq 1) $r.raw

    # ...y el ; final de una sentencia sola ya no se cuela dentro del valor (era un bug silencioso).
    $r = Exec "INSERT INTO Libro li VALUES li.titulo='Punto y coma final';"
    Check 'una sentencia sola con ; final funciona' ($r.json.type -eq 'DML' -and $r.json.affectedRows -eq 1) $r.raw
    $r = Exec "SELECT l.titulo FROM Libro l WHERE l.titulo LIKE 'Punto y coma final%'"
    Check 'el ; final no quedo dentro del valor' ($r.json.rowCount -eq 1 -and $r.json.rows[0][0] -eq 'Punto y coma final') ($r.json.rows[0] -join '|')

    # --- dry-run: cuenta sin commitear, y solo se aplica si se confirma ---
    $r = Exec "SELECT e.salario FROM Empleado e WHERE e.nombre = 'Ana Gomez'"
    $salarioAntes = $r.json.rows[0][0]

    $r = Exec "UPDATE Empleado e SET e.salario = 999999 WHERE e.nombre = 'Ana Gomez'" $true
    Check 'el dry-run de un UPDATE cuenta las filas' ($r.status -eq 200 -and $r.json.type -eq 'DML' -and $r.json.affectedRows -eq 1) $r.raw
    $r = Exec "SELECT e.salario FROM Empleado e WHERE e.nombre = 'Ana Gomez'"
    Check 'el dry-run NO cambio los datos (rollback)' ($r.json.rows[0][0] -eq $salarioAntes) "$($r.json.rows[0][0]) vs $salarioAntes"

    $r = Exec "UPDATE Empleado e SET e.salario = 999999 WHERE e.nombre = 'Ana Gomez'"
    Check 'confirmado (sin dry-run) el UPDATE se aplica' ($r.json.affectedRows -eq 1) $r.raw
    $r = Exec "SELECT e.salario FROM Empleado e WHERE e.nombre = 'Ana Gomez'"
    Check 'el UPDATE confirmado quedo en la base' ($r.json.rows[0][0] -eq 999999) $r.json.rows[0][0]

    $r = Exec 'SELECT count(l) FROM Libro l'
    $librosAntes = $r.json.rows[0][0]
    $r = Exec 'DELETE FROM Libro l' $true
    Check 'el dry-run de un DELETE cuenta todas las filas' ($r.status -eq 200 -and $r.json.affectedRows -eq $librosAntes) $r.raw
    $r = Exec 'SELECT count(l) FROM Libro l'
    Check 'el dry-run del DELETE no borro nada' ($r.json.rows[0][0] -eq $librosAntes) "$($r.json.rows[0][0]) vs $librosAntes"

    # Si el dry-run dejara la transaccion abierta, la consola quedaria trabada: se comprueba que no.
    $r = Exec 'SELECT count(l) FROM Libro l'
    Check 'despues de un dry-run la consola sigue respondiendo' ($r.status -eq 200 -and $r.json.rows[0][0] -eq $librosAntes) $r.raw

    $r = Exec 'DESC Libro'
    Check 'despues de un dry-run las lecturas siguen bien' ($r.status -eq 200 -and $r.json.rowCount -ge 4) $r.status

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
    Check 'la consola carga tambien la etiqueta de Autor sin join fetch' `
          ($r.json.rows[0][$posAutor] -eq '1 (Jorge Luis Borges)') $r.json.rows[0][$posAutor]

    $r = Exec "from Libro l where l.genero = 'NOVELA' order by l.id desc"
    Check 'from <Entidad> con WHERE y ORDER BY sigue aplanado' (($r.json.headers -join ',') -eq $atributosDesc) ($r.json.headers -join ',')

    $r = Exec 'from Libro l join fetch l.autor order by l.id'
    Check 'join fetch tambien se aplana (la fila es solo la entidad)' ($r.json.headers.Count -eq 9) $r.json.headers.Count
    $posAutor = [array]::IndexOf($r.json.headers, 'autor')
    Check 'un @ManyToOne cargado suma su etiqueta HQL Console al id' `
          ($r.json.rows[0][$posAutor] -eq '1 (Jorge Luis Borges)') $r.json.rows[0][$posAutor]
    Check 'una relacion con etiqueta se tipa como TEXTO para ordenarla correctamente' `
          ($r.json.types[$posAutor] -eq 'TEXTO') $r.json.types[$posAutor]

    $r = Exec 'select l from Libro l'
    Check 'con SELECT explicito NO se aplana' ($r.json.headers.Count -eq 1 -and $r.json.rows[0][0] -eq 'Libro#1') $r.json.rows[0][0]

    $r = Exec 'from Libro l join l.autor a'
    Check 'un join explicito devuelve las dos raices' ($r.json.headers.Count -eq 2) ($r.json.headers -join ',')

    # --- SELECT * FROM <Entidad>: lo mismo que FROM <Entidad> ---
    $r = Exec 'SELECT * FROM Libro'
    Check 'SELECT * FROM <Entidad> aplana igual que from <Entidad>' `
          (($r.json.headers -join ',') -eq $atributosDesc -and $r.json.rowCount -eq $esperadas) "$($r.json.headers -join ',') ($($r.json.rowCount) filas)"
    Check 'SELECT * carga la etiqueta de la relacion igual que from' `
          ($r.json.rows[0][$posAutor] -eq '1 (Jorge Luis Borges)') $r.json.rows[0][$posAutor]
    $r = Exec "SELECT * FROM Libro l WHERE l.genero = 'NOVELA' ORDER BY l.id LIMIT 100"
    Check 'SELECT * con alias, WHERE, ORDER BY y LIMIT sigue andando' `
          ($r.status -eq 200 -and ($r.json.headers -join ',') -eq $atributosDesc) $r.raw

    $r = Exec 'SELECT * FROM Empleado e WHERE e.id = 1'
    Check 'SELECT * FROM ... WHERE devuelve una fila aplanada' `
          ($r.json.rowCount -eq 1 -and ($r.json.headers -join ',') -eq 'id,nombre,salario,ingreso,departamento') ($r.json.headers -join ',')
    Check 'SELECT * con WHERE carga la etiqueta sin alterar el HQL' ($r.json.rows[0][4] -eq '1 (Sistemas)') $r.json.rows[0][4]

    # Un select explicito que no es "*" no se toca: sigue siendo una sola columna con "Tipo#id".
    $r = Exec 'select l from Libro l'
    Check 'un SELECT que no es * no se aplana' ($r.json.headers.Count -eq 1) ($r.json.headers -join ',')

    # --- LIMIT al final de la consulta ---
    $r = Exec 'from Empleado limit 2'
    Check 'from <Entidad> LIMIT n trae solo n filas' ($r.json.rowCount -eq [Math]::Min($MaxRows, 2) -and $r.json.truncated -eq $false) "rowCount=$($r.json.rowCount) truncated=$($r.json.truncated)"
    Check 'el LIMIT aplicado se informa' ($r.json.message -match 'LIMIT 2') $r.json.message

    $r = Exec 'SELECT e.id, e.nombre FROM Empleado e ORDER BY e.id LIMIT 3'
    Check 'SELECT largo con ORDER BY y LIMIT al final trae 3 filas' ($r.json.rowCount -eq [Math]::Min($MaxRows, 3)) $r.json.rowCount

    $r = Exec 'SELECT e.id FROM Empleado e ORDER BY e.id DESC LIMIT 1'
    $idMayor = $r.json.rows[0][0]
    $r = Exec 'SELECT * FROM Empleado e ORDER BY e.id DESC LIMIT 1'
    Check 'SELECT * con ORDER BY DESC y LIMIT funciona' `
          ($r.json.rowCount -eq 1 -and $r.json.rows[0][0] -eq $idMayor) ($r.json.rows[0] -join '|')

    # El LIMIT es gramatica de la consola: Hibernate no lo entiende, asi que se saca antes.
    $r = Exec 'from Empleado limit 1'
    Check 'el LIMIT no llega a Hibernate (no hay error de sintaxis)' ($r.status -eq 200) $r.raw

    # Un "limit" que no es la clausula del final no se toca: adentro de un literal es texto.
    $r = Exec "SELECT e.nombre FROM Empleado e WHERE e.nombre LIKE '%limit 5%'"
    Check 'un limit dentro de un literal no se confunde con la clausula' ($r.status -eq 200 -and $r.json.rowCount -eq 0) $r.raw

    $r = Exec 'from Empleado limit'
    Check 'LIMIT sin numero avisa' ($r.status -eq 400 -and $r.json.error -match 'LIMIT espera un') $r.raw
    $r = Exec 'from Empleado limit abc'
    Check 'LIMIT con texto avisa' ($r.status -eq 400 -and $r.json.error -match 'LIMIT espera un') $r.raw
    $r = Exec 'from Empleado limit 0'
    Check 'LIMIT 0 avisa' ($r.status -eq 400 -and $r.json.error -match 'mayor que cero') $r.raw

    # --- entidades y atributos son case sensitive ---
    $r = Exec 'DESC libro'
    Check 'DESC con la entidad en minuscula falla sin sugerencia' ($r.status -eq 400 -and $r.json.error -eq 'Entidad no encontrada: libro') $r.raw
    $r = Exec 'from libro'
    Check 'from con la entidad en minuscula falla sin sugerencia' ($r.status -eq 400 -and $r.json.error -eq 'Entidad no encontrada: libro') $r.raw
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
    Check 'la pagina trae el ejecutar-por-seleccion' ($page.Content -match 'ta\.selectionStart' -and $page.Content -match 'function textoAejecutar') 'falta el codigo de seleccion'
    Check 'Ctrl+Enter ejecuta desde el textarea' ($page.Content -match "ta\.addEventListener\('keydown'" -and $page.Content -match "ev\.key === 'Enter'") 'falta el atajo'
    Check 'la pagina trae el ejecutar-por-parrafo' ($page.Content -match 'function rangoParrafo' -and $page.Content -match 'function lineasDe' -and $page.Content -match 'INICIO funciones puras') 'falta el calculo del parrafo'
    Check 'una seleccion en blanco cae en el parrafo' ($page.Content -match 'recorte\.trim\(\)') 'no esta el fallback de seleccion vacia'

    # --- el editor ocupa todo el alto: se ejecuta exclusivamente con Ctrl+Enter ---
    Check 'el editor no tiene pie ni boton Ejecutar' ($page.Content -notmatch 'pie-editor' -and $page.Content -notmatch 'id="pista"') 'quedo el pie del editor'

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

// --- filtro y seguridad de la solapa SQL ---
check('sql: normaliza filtro', normalizarFiltro('  LiBrOs  '), 'libros');
check('sql: SELECT es solo lectura', esSoloLectura(' SELECT '), true);
check('sql: DROP no es solo lectura', esSoloLectura('drop'), false);
check('sql: clave SQL propia', claveDeTexto('sql'), 'hql-console.consulta-sql');
check('sql: clave HQL por defecto', claveDeTexto('otro'), 'hql-console.consulta');
var tablasFiltro = [{nombre:'LIBROS',tipo:'TABLA'},{nombre:'V_LIBROS',tipo:'VISTA'}];
check('sql: filtra por texto', filtrarTablas(tablasFiltro, 'v_', 'Todas').length, 1);
check('sql: filtra por tipo', filtrarTablas(tablasFiltro, '', 'Tablas').length, 1);
check('sql: filtro no muta la lista', tablasFiltro.length, 2);

// --- el alert del INSERT (el texto va con escapes porque este archivo lo lee PowerShell como ANSI) ---
check('insert: una fila', mensajeInsercion(1, 1), 'Se insert\u00f3 1 fila');
check('insert: varias filas en una sentencia', mensajeInsercion(3, 1), 'Se insertaron 3 filas');
check('insert: varias filas en varias sentencias', mensajeInsercion(5, 4), 'Se insertaron 5 filas en 4 sentencias');
check('insert: sin el dato de sentencias asume una', mensajeInsercion(2), 'Se insertaron 2 filas');
check('insert: cero filas', mensajeInsercion(0, 1), 'Se insertaron 0 filas');

// --- la confirmacion antes de commitear (dry-run de UPDATE y DELETE) ---
check('confirmar: un update', pideConfirmacion('UPDATE Libro li SET li.titulo=1'), true);
check('confirmar: un update con espacios y minusculas', pideConfirmacion('  update Libro li SET li.titulo=1'), true);
check('confirmar: un delete', pideConfirmacion('DELETE FROM Libro l'), true);
check('confirmar: un insert no', pideConfirmacion('INSERT INTO Libro li VALUES li.titulo=1'), false);
check('confirmar: un select no', pideConfirmacion('SELECT e.id FROM Empleado e'), false);
check('confirmar: un desc no', pideConfirmacion('DESC Libro'), false);
check('confirmar: un lote de insert no', pideConfirmacion('INSERT INTO Libro li VALUES li.titulo=1; INSERT INTO Libro li VALUES li.titulo=2'), false);
check('mensaje: modificar una fila', mensajeConfirmacion('UPDATE x', 1, false), 'Se van a modificar 1 fila.\n\n\u00bfConfirm\u00e1s?');
check('mensaje: borrar varias', mensajeConfirmacion('DELETE FROM Libro l', 4, false), 'Se van a borrar 4 filas.\n\n\u00bfConfirm\u00e1s?');
check('mensaje: avisa si el tope trunco', mensajeConfirmacion('UPDATE x', 3, true), 'Se van a modificar 3 filas.\n\n\u00bfConfirm\u00e1s?\n\n(se alcanz\u00f3 el tope de filas: el resto NO se toca)');

// --- la lista de entidades clickeable (solo el DESC sin argumentos) ---
check('desc sin args: desc', esDescSinArgumentos('DESC'), true);
check('desc sin args: describe', esDescSinArgumentos('describe'), true);
check('desc sin args: con espacios y mayusculas', esDescSinArgumentos('  DeSc  '), true);
check('desc sin args: con entidad no', esDescSinArgumentos('DESC Libro'), false);
check('desc sin args: un select no', esDescSinArgumentos('SELECT e.id FROM Empleado e'), false);
check('desc sin args: un from no', esDescSinArgumentos('from Libro'), false);

// --- el detalle de una entidad (para navegar las relaciones) ---
check('desc de entidad: desc Libro', esDescDeUnaEntidad('DESC Libro'), true);
check('desc de entidad: describe Libro', esDescDeUnaEntidad('  describe  Libro '), true);
check('desc de entidad: desc a secas no', esDescDeUnaEntidad('DESC'), false);
check('desc de entidad: un select no', esDescDeUnaEntidad('SELECT e.id FROM Empleado e'), false);
check('desc de entidad: una palabra que empieza igual no', esDescDeUnaEntidad('descripcion'), false);
check('desc de entidad: dos palabras no', esDescDeUnaEntidad('DESC Libro li'), false);

// --- el nombre de la entidad, que es lo que usa el panel lateral ---
check('entidad de desc: devuelve el nombre', entidadDeDesc('DESC Libro'), 'Libro');
check('entidad de desc: describe tambien', entidadDeDesc('  Describe  Empleado '), 'Empleado');
check('entidad de desc: mayusculas mezcladas', entidadDeDesc('deSc Autor'), 'Autor');
check('entidad de desc: desc a secas no tiene entidad', entidadDeDesc('DESC'), null);
check('entidad de desc: un select no', entidadDeDesc('SELECT e.id FROM Empleado e'), null);
check('entidad de desc: dos palabras no', entidadDeDesc('DESC Libro li'), null);
check('entidad de desc: una palabra que empieza igual no', entidadDeDesc('descripcion'), null);

// --- el orden de la grilla (click en un header) ---
check('tipo: usa el tipo que manda el backend', tipoDeColumna(['NUMERO','TEXTO'], 0), 'NUMERO');
check('tipo: la segunda columna', tipoDeColumna(['NUMERO','TEXTO'], 1), 'TEXTO');
check('tipo: sin tipos cae en TEXTO', tipoDeColumna([], 0), 'TEXTO');
check('tipo: indice fuera de rango cae en TEXTO', tipoDeColumna(['NUMERO'], 3), 'TEXTO');
check('tipo: sin lista de tipos cae en TEXTO', tipoDeColumna(null, 0), 'TEXTO');

// numeros: orden numerico y no alfabetico (9 antes que 10)
check('comparar numeros: 9 < 10', compararCeldas(9, 10, 'NUMERO') < 0, true);
check('comparar numeros: 10 > 9', compararCeldas(10, 9, 'NUMERO') > 0, true);
check('comparar numeros: iguales', compararCeldas(5, 5, 'NUMERO'), 0);
// ...y como texto daria al reves: ese es el bug que evita el tipo que manda el backend
check('comparar texto: "10" < "9"', compararCeldas('10', '9', 'TEXTO') < 0, true);
check('comparar numeros de texto', compararCeldas('777', '88', 'NUMERO') > 0, true);

// texto: alfabetico puro (lo que significa ordenar texto), sin que las mayusculas partan la lista
check('comparar texto: Ana < Beto', compararCeldas('Ana', 'Beto', 'TEXTO') < 0, true);
check('comparar texto: "10" < "9" (alfabetico, no numerico)', compararCeldas('10', '9', 'TEXTO') < 0, true);
check('comparar texto: "2" < "10" (alfabetico, no natural)', compararCeldas('2', '10', 'TEXTO') > 0, true);
check('comparar texto: ignora mayusculas', compararCeldas('ana', 'Ana', 'TEXTO'), 0);

// fechas: cronologico
check('comparar fechas: 1994 < 2024', compararCeldas('1994-11-23', '2024-01-01', 'FECHA') < 0, true);
check('comparar fechas: mismo dia', compararCeldas('1994-11-23', '1994-11-23', 'FECHA'), 0);
check('comparar fechas: con hora', compararCeldas('2024-01-01T10:00', '2024-01-01T09:00', 'FECHA') > 0, true);

// booleanos
check('comparar booleanos: false < true', compararCeldas(false, true, 'BOOLEANO') < 0, true);
check('comparar booleanos: true > false', compararCeldas(true, false, 'BOOLEANO') > 0, true);

// los NULL van al final SIEMPRE, en las dos direcciones. Ojo: multiplicar el resultado por -1 no
// sirve para probarlo (compararCeldas(null,x) devuelve 1 en las dos direcciones a proposito): hay
// que mirar el resultado de ordenar de verdad.
check('ordenar: null al final en ascendente', ordenarFilas([[5],[null],[3]], 0, 'NUMERO', 'asc').map(function(f){return f[0];}).join(','), '3,5,');
check('ordenar: null al final tambien en descendente', ordenarFilas([[5],[null],[3]], 0, 'NUMERO', 'desc').map(function(f){return f[0];}).join(','), '5,3,');
check('comparar: dos null son iguales', compararCeldas(null, undefined, 'TEXTO'), 0);

// la direccion
check('direccion: ascendente no invierte', factorDeDireccion('asc'), 1);
check('direccion: descendente invierte', factorDeDireccion('desc'), -1);
check('direccion siguiente: de asc a desc', direccionSiguiente('asc'), 'desc');
check('direccion siguiente: de desc a asc', direccionSiguiente('desc'), 'asc');

// ordenarFilas: ordena, devuelve una copia y no toca el original
var filas = [[3,'c'],[1,'a'],[2,'b']];
check('ordenar: ascendente', ordenarFilas(filas, 0, 'NUMERO', 'asc').map(function(f){return f[0];}).join(','), '1,2,3');
check('ordenar: descendente', ordenarFilas(filas, 0, 'NUMERO', 'desc').map(function(f){return f[0];}).join(','), '3,2,1');
check('ordenar: no toca las filas originales', filas.map(function(f){return f[0];}).join(','), '3,1,2');
check('ordenar: por texto', ordenarFilas(filas, 1, 'TEXTO', 'asc').map(function(f){return f[1];}).join(','), 'a,b,c');
check('ordenar: sin filas no explota', ordenarFilas(null, 0, 'TEXTO', 'asc').length, 0);
check('ordenar: tolera filas de distinto largo', ordenarFilas([[1],[2,'x']], 1, 'TEXTO', 'asc').length, 2);

// --- las acciones visibles de entidad: el INSERT de ejemplo y el SELECT * ---
check('id: reconoce la marca', esAtributoId('id*'), true);
check('id: un atributo normal no', esAtributoId('titulo'), false);
check('id: saca la marca', sinMarcaDeId('id*'), 'id');
check('id: deja el nombre igual si no tiene marca', sinMarcaDeId('titulo'), 'titulo');

// Las filas son las del DESC: [ATRIBUTO, TIPO JAVA, CAMPO, TIPO SQL]
var descLibro = [['id*','Long','ID','BIGINT'],['titulo','String','TITULO','VARCHAR'],['autor','Autor','ID_AUTOR','BIGINT'],['precio','BigDecimal','PRECIO','DECIMAL']];
var ins = insertDeEntidad('Libro', descLibro);
check('insert: lleva el comentario arriba', ins.indexOf('// Completa y ejecuta esta sentencia') === 0, true);
check('insert: excluye el id', ins.indexOf('(titulo,autor,precio)') > 0, true);
check('insert: no escribe la columna id', ins.indexOf('id,') < 0, true);
check('insert: el texto va entre comillas', ins.indexOf("'999'") > 0, true);
check('insert: el numero va pelado', ins.indexOf(', 999') > 0 || ins.indexOf(',999') > 0, true);
check('insert: termina en punto y coma', ins.slice(-2) === ');', true);
check('insert: la entidad es la que se clickeo', insertDeEntidad('Autor', [['id*','Long','ID','BIGINT'],['nombre','String','NOMBRE','VARCHAR']]).indexOf('INSERT INTO Autor (nombre)') > 0, true);
check('insert: sin columnas no rompe', insertDeEntidad('X', []).indexOf('INSERT INTO X () VALUES ()') > 0, true);

// Los tipos tienen que dar valores que la consola entienda: fecha ISO, NOW, booleano
check('valor: Long es numerico', valorDeEjemplo('Long'), '999');
check('valor: BigDecimal es numerico', valorDeEjemplo('BigDecimal'), '999');
check('valor: String va entre comillas', valorDeEjemplo('String'), "'999'");
check('valor: LocalDate es ISO', valorDeEjemplo('LocalDate'), "'2024-01-01'");
check('valor: LocalDateTime usa NOW', valorDeEjemplo('LocalDateTime'), 'NOW');
check('valor: Boolean es false', valorDeEjemplo('Boolean'), 'false');
check('tipo numerico: Long si', esTipoNumerico('Long'), true);
check('tipo numerico: int si', esTipoNumerico('int'), true);
check('tipo numerico: String no', esTipoNumerico('String'), false);
check('tipo numerico: LocalDate no', esTipoNumerico('LocalDate'), false);

check('select: arma el SELECT * con LIMIT', selectDeEntidad('Libro'), 'SELECT * FROM Libro LIMIT 100');
check('select: el limite sale de la constante', selectDeEntidad('X').indexOf('LIMIT 100') > 0, true);

// --- el INSERT generado se mete en el parrafo del cursor, sin pisar lo que habia ---
var doc = 'SELECT 1\n\nSELECT 2';
var puesto = insertarEnParrafo(doc, doc.indexOf('SELECT 1') + 2, 'INSERT INTO X (a) VALUES (1);');
check('insertar: no borra lo que habia', puesto.texto.indexOf('SELECT 1') === 0, true);
check('insertar: agrega despues del parrafo del cursor', puesto.texto.indexOf('SELECT 1\n\nINSERT INTO X') === 0, true);
check('insertar: el parrafo de abajo queda intacto', puesto.texto.indexOf('SELECT 2') > 0, true);
check('insertar: deja una linea en blanco antes', puesto.texto.indexOf('\n\nINSERT') > 0, true);
check('insertar: deja una linea en blanco despues', puesto.texto.indexOf(');\n\nSELECT 2') > 0, true);
check('insertar: no duplica los saltos que ya habia', puesto.texto.indexOf(');\n\n\n') < 0, true);
// Con el cursor ARRIBA de todo lo escrito, el INSERT va al principio y no debajo del primer parrafo.
var conCursorArriba = '\n\nSELECT 1';
var alPrincipio = insertarEnParrafo(conCursorArriba, 0, 'INSERT INTO X (a) VALUES (1);');
check('insertar arriba: el INSERT queda primero', alPrincipio.texto.indexOf('INSERT INTO X') === 0, true);
check('insertar arriba: no queda debajo del primer parrafo', alPrincipio.texto.indexOf('INSERT INTO X') < alPrincipio.texto.indexOf('SELECT 1'), true);
check('insertar arriba: separa del parrafo de abajo', alPrincipio.texto.indexOf(');\n\nSELECT 1') > 0, true);
check('insertar arriba: lo insertado queda seleccionado', alPrincipio.texto.substring(alPrincipio.seleccion.inicio, alPrincipio.seleccion.fin), 'INSERT INTO X (a) VALUES (1);');
check('insertar arriba: la seleccion arranca en 0', alPrincipio.seleccion.inicio, 0);
// En un editor vacio pasa lo mismo: el INSERT arranca en 0 y no deja saltos colgando.
var enVacio = insertarEnParrafo('', 0, 'INSERT INTO Z (c) VALUES (3);');
check('insertar arriba: en un editor vacio arranca en 0', enVacio.texto.indexOf('INSERT INTO Z'), 0);
check('insertar arriba: en un editor vacio no deja saltos', enVacio.texto, 'INSERT INTO Z (c) VALUES (3);');
// Y si el cursor esta arriba pero ya hay texto abajo, el orden se respeta.
var conTextoAbajo = insertarEnParrafo('SELECT 9', 0, 'INSERT INTO W (d) VALUES (4);');
check('insertar arriba: con el cursor en el primer parrafo sigue yendo despues de el', conTextoAbajo.texto.indexOf('INSERT INTO W') > conTextoAbajo.texto.indexOf('SELECT 9'), true);

// El cursor queda adentro del parentesis de VALUES, que es donde se completan los valores. OJO: el
// primer parentesis de la sentencia es el de la LISTA DE COLUMNAS, que es donde NO va.
var esperadoCursor = puesto.texto.indexOf('VALUES (') + 'VALUES ('.length;
check('insertar: el cursor queda despues del parentesis de VALUES', puesto.cursor, esperadoCursor);
check('insertar: el cursor NO queda en el parentesis de las columnas', puesto.cursor !== puesto.texto.indexOf('(') + 1, true);
check('insertar: lo que sigue al cursor son los valores', puesto.texto.substring(puesto.cursor, puesto.cursor + 3), '1);');
check('posicion: con VALUES', posicionDeValores("INSERT INTO X (a,b) VALUES (1,2);"), "INSERT INTO X (a,b) VALUES (".length);
check('posicion: una sentencia sin VALUES cae al primer parentesis', posicionDeValores('SELECT count(e)'), 'SELECT count('.length);
check('posicion: sin parentesis va al final', posicionDeValores('DESC Libro'), 'DESC Libro'.length);
check('posicion: no se confunde con un VALUES en minusculas', posicionDeValores('insert into x (a) values (1)'), 'insert into x (a) values ('.length);
check('insertar: la sentencia queda entera y en su lugar', puesto.texto.indexOf('INSERT INTO X (a) VALUES (1);') > 0, true);
// La seleccion cubre EXACTAMENTE la sentencia: es lo que hace visible donde quedo el INSERT.
check('insertar: lo insertado queda seleccionado', puesto.texto.substring(puesto.seleccion.inicio, puesto.seleccion.fin), 'INSERT INTO X (a) VALUES (1);');
check('insertar: la seleccion empieza donde empieza la sentencia', puesto.seleccion.inicio, puesto.texto.indexOf('INSERT INTO X'));
check('insertar: la seleccion no se lleva los saltos de linea', /^[^\n]*$/.test(puesto.texto.substring(puesto.seleccion.inicio, puesto.seleccion.fin)), true);
// El cursor queda adentro de VALUES, que es donde se completa el primer valor.
check('insertar: el cursor queda dentro de la sentencia seleccionada', puesto.cursor > puesto.seleccion.inicio && puesto.cursor < puesto.seleccion.fin, true);
// Con el cursor al final del documento, el insert va al final y no al principio.
var alFinal = insertarEnParrafo('SELECT 1\n\n', 10, 'INSERT INTO Y (b) VALUES (2);');
check('insertar: con el cursor al final agrega al final', alFinal.texto.indexOf('INSERT INTO Y') > alFinal.texto.indexOf('SELECT 1'), true);
// En un editor vacio, el insert queda solo.
var vacio = insertarEnParrafo('', 0, 'INSERT INTO Z (c) VALUES (3);');
check('insertar: en un editor vacio no agrega lineas de mas al principio', vacio.texto.indexOf('INSERT INTO Z') === 0, true);
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

        # El JS vive en el recurso HTML: una barra invertida mal puesta lo rompe sin que falle la
        # compilacion. Esto lo caza sin abrir un navegador (paso dos veces durante el desarrollo).
        $jsPagina = [regex]::Match($page.Content, '(?s)<script>(.*?)</script>').Groups[1].Value
        $archivoPagina = Join-Path $env:TEMP 'hql-console-pagina.js'
        Set-Content -Path $archivoPagina -Value $jsPagina -Encoding UTF8
        $previo2 = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $salidaCheck = & node --check $archivoPagina 2>&1
        $codigoCheck = $LASTEXITCODE
        $ErrorActionPreference = $previo2
        Check 'el JS de la pagina parsea (node --check)' ($codigoCheck -eq 0) ($salidaCheck -join ' | ')
    }

    # --- el alert del INSERT ---
    Check 'la pagina avisa los INSERT con un alert' ($page.Content -match 'function mensajeInsercion' -and $page.Content -match 'alert\(mensajeInsercion') 'no esta el alert del INSERT'
    Check 'el alert del INSERT usa el detector actual' ($page.Content -match 'const esInsercion = /' -and $page.Content -match 'insert\\b/i\.test\(hql\)') 'no se detecta el INSERT'

    # --- layout partido con divisor movible ---
    Check 'la pagina trae el layout partido' ($page.Content -match 'id="panel-editor"' -and $page.Content -match 'id="panel-resultado"') 'faltan los paneles'
    Check 'la separacion es vertical y movible' ($page.Content -match 'id="divisor"' -and $page.Content -match 'cursor:col-resize' -and $page.Content -match 'pointerdown') 'falta el divisor arrastrable'
    Check 'el ancho del editor es configurable por CSS' ($page.Content -match '--ancho-editor' -and $page.Content -match 'flex:0 0 var\(--ancho-editor\)') 'no esta el ancho variable'
    Check 'los resultados viven en el panel derecho' (([regex]::Match($page.Content, '(?s)id="panel-resultado".*?id="crudo"')).Success -and $page.Content -match 'id="vacio"') 'los resultados no estan en el panel derecho'

    # --- el panel lateral de entidades ---
    Check 'la pagina trae el panel de entidades' ($page.Content -match 'id="panel-entidades"' -and $page.Content -match 'id="lista-entidades"') 'falta el panel de entidades'
    Check 'el panel tiene el control de contraer y expandir' ($page.Content -match 'id="toggle-entidades"' -and $page.Content -match 'function aplicarEntidades') 'falta el control del panel'
    Check 'el panel se contrae y se recuerda' ($page.Content -match "classList\.toggle\('contraido'" -and $page.Content -match 'CLAVE_ENTIDADES') 'no se contrae o no se recuerda'
    Check 'el panel es angosto, solo para los nombres' ($page.Content -match '#panel-entidades \{ flex:0 0 auto; width:150px' -and $page.Content -match '\.entidad-item') 'no esta el ancho del panel'
    Check 'la lista de entidades sale del DESC sin argumentos' ($page.Content -match 'function pintarEntidades' -and $page.Content -match "pedir\('DESC', false\)") 'la lista no sale del DESC'
    Check 'el click de una entidad ejecuta su DESC' ($page.Content -match "ejecutarTexto\('DESC ' \+ nombre") 'el click no ejecuta el DESC'
    Check 'el click de una tabla ejecuta el DESC SQL emulado' ($page.Content -match "ejecutarTextoSql\('DESC '\s*\+\s*tablaSql\.nombre") 'el click de SQL no ejecuta el DESC emulado'
    Check 'las FK SQL abren el DESC de la tabla relacionada abajo' ($page.Content -match 'function hacerRelacionesSqlClickeables' -and $page.Content -match 'function mostrarDetalleSql' -and $page.Content -match "pedir\('DESC ' \+ tablaSql") 'las FK SQL no abren el detalle emulado'
    Check 'el click no pisa el editor' ($page.Content -notmatch "ta\.value = 'DESC '") 'el panel de entidades pisa el textarea'
    Check 'la entidad elegida se marca en el panel' ($page.Content -match 'function marcarEntidadElegida' -and $page.Content -match "classList\.toggle\('elegida'") 'no se marca la entidad elegida'
    Check 'la lista se pide sola al abrir la pagina' ($page.Content -match '(?s)function cabecerasDeFilas.*?asegurarEntidades\(\);') 'no se pide la lista al abrir'
    Check 'el arrastre del divisor descuenta el panel de entidades' ($page.Content -match 'panelEditor\.getBoundingClientRect') 'el divisor se mide desde el borde del split'

    # --- persistencia del texto ---
    Check 'el texto del editor se persiste en el navegador' ($page.Content -match "CLAVE_TEXTO = 'hql-console\.consulta'" -and $page.Content -match 'ALMACEN\.setItem\(CLAVE_TEXTO') 'no se guarda el texto'
    Check 'el texto se restituye al abrir la pagina' ($page.Content -match 'ta\.value = \(textoGuardado === null') 'no se restituye el texto'
    # Al abrir, el foco arranca en el editor y el cursor en el caracter 0.
    Check 'al abrir el foco queda en el editor con el cursor en 0' `
          ($page.Content -match '(?s)ta\.value = \(textoGuardado === null.*?ta\.focus\(\);\s*ta\.setSelectionRange\(0, 0\)') 'no se enfoca el editor al abrir'
    Check 'se guarda tambien mientras se escribe' ($page.Content -match "ta\.addEventListener\('input'" -and $page.Content -match 'setTimeout\(guardarTexto') 'no hay guardado al tipear'
    Check 'el ancho del divisor tambien se persiste' ($page.Content -match 'CLAVE_ANCHO') 'no se persiste el ancho'

    # --- nada de cache: si el navegador guarda la pagina, los cambios no se ven ---
    Check 'la pagina se sirve sin cache' ("$($page.Headers['Cache-Control'])" -match 'no-store') "Cache-Control: $($page.Headers['Cache-Control'])"

    # --- la confirmacion antes de commitear: lo que la pagina hace con el dry-run ---
    Check 'la pagina hace el dry-run antes de confirmar' ($page.Content -match 'pedir\(hql, true\)' -and $page.Content -match 'pideConfirmacion\(hql\)') 'no esta el flujo de confirmacion'
    Check 'la pagina manda dryRun al servidor' ($page.Content -match 'dryRun: dryRun') 'no manda dryRun'
    Check 'la pagina confirma con el conteo' ($page.Content -match 'confirm\(mensajeConfirmacion') 'no usa confirm con el conteo'
    Check 'la pagina avisa cuando se cancela' ($page.Content -match 'Cancelado: no se modific') 'no avisa la cancelacion'

    # --- DESC: la lista de entidades es clickeable y el detalle va abajo, en un split horizontal ---
    Check 'la pagina trae el split del detalle' ($page.Content -match 'id="divisor-h"' -and $page.Content -match 'id="panel-detalle"' -and $page.Content -match 'id="t-detalle"') 'falta el panel de detalle'
    Check 'el detalle se redimensiona y se recuerda' ($page.Content -match 'cursor:row-resize' -and $page.Content -match '--alto-detalle' -and $page.Content -match 'CLAVE_ALTO') 'falta el divisor del detalle'
    Check 'solo el DESC sin argumentos arma la lista clickeable' ($page.Content -match 'esDescSinArgumentos\(hql\)' -and $page.Content -match 'hacerListaClickeable\(cabeceras\)') 'no se arma la lista clickeable'
    Check 'la lista clickeable sale de la columna ENTIDAD' ($page.Content -match "indexOf\('ENTIDAD'\)") 'no se busca la columna ENTIDAD'
    Check 'el click pide el DESC de esa entidad' ($page.Content -match "pedir\('DESC ' \+ entidad") 'el click no pide el detalle'
    Check 'el detalle tiene su propio error (no borra la lista)' ($page.Content -match 'detalleError.textContent' -and $page.Content -match 'abrirDetalle\(\)') 'el detalle no maneja su error'
    Check 'otro resultado cierra el detalle' ($page.Content -match 'cerrarDetalle\(\)') 'no se cierra el detalle'

    # --- navegar las relaciones @ManyToOne desde el detalle ---
    Check 'cada sentencia resetea el panel derecho antes de mostrar' ($page.Content -match '(?s)resetPanelDerecho\(\);\s*const cabeceras = mostrarResultado') 'no se resetea el panel derecho'
    Check 'el reset cierra el detalle y borra lo anterior' ($page.Content -match '(?s)function resetPanelDerecho\(\)(.*?)crudoPre\.textContent' -and $page.Content -match 'tablaDetalle\.textContent = ' -and $page.Content -match 'cajaTabla\.hidden = true') 'el reset no limpia todo'
    Check 'el reset tambien corre cuando la sentencia falla' ($page.Content -match '(?s)function mostrarError\(datos\)(.*?)resetPanelDerecho\(\)') 'un error deja el panel viejo'
    # El bug que destapo esto: un panel con display:flex en su propia regla se quedaba visible aunque
    # el JS le pusiera hidden, porque el id pesa mas que la regla del navegador.
    Check 'el CSS respeta el atributo hidden' ($page.Content -match '\[hidden\] \{ display:none !important') 'un panel con display:flex puede quedar visible con hidden'
    Check 'el DESC de una entidad arma las relaciones clickeables' ($page.Content -match 'esDescDeUnaEntidad\(hql\)' -and $page.Content -match 'hacerRelacionesClickeables\(cabeceras, tabla\)') 'no se cablean las relaciones del detalle'
    Check 'la relacion se detecta por la columna TIPO JAVA' ($page.Content -match "indexOf\('TIPO JAVA'\)") 'no se busca la columna TIPO JAVA'
    Check 'la lista de entidades se pide sola si no se tiene' ($page.Content -match 'asegurarEntidades' -and $page.Content -match "pedir\('DESC', false\)") 'no se asegura la lista de entidades'
    Check 'desde el panel de abajo tambien se encadena' ($page.Content -match 'hacerRelacionesClickeables\(cabeceras, tablaDetalle\)') 'el detalle de abajo no encadena'
    Check 'la fila elegida se marca y se desmarca' ($page.Content -match 'marcarElegida' -and $page.Content -match "classList.remove\('fila-elegida'\)") 'no se marca la fila elegida'

    $r = Exec 'SELECT e.id, e.nombre, e.salario, e.ingreso FROM Empleado e'
    Check 'el resultado trae un tipo por columna' (($r.json.types -join ',') -eq 'NUMERO,TEXTO,NUMERO,FECHA') ($r.json.types -join ',')
    Check 'la cantidad de tipos coincide con la de headers' ($r.json.types.Count -eq $r.json.headers.Count) "$($r.json.types.Count) vs $($r.json.headers.Count)"

    $r = Exec 'from Empleado e'
    Check 'from <Entidad> tipa TEXTO una relacion cuyo destino ofrece etiqueta HQL Console' `
          (($r.json.types -join ',') -eq 'NUMERO,TEXTO,NUMERO,FECHA,TEXTO') ($r.json.types -join ',')

    $r = Exec 'SELECT e.nombre FROM Empleado e WHERE e.id = 999'
    Check 'con 0 filas el tipo es OTRO (no hay nada que mirar)' (($r.json.types -join ',') -eq 'OTRO') ($r.json.types -join ',')

    $r = Exec 'DESC Libro'
    Check 'el DESC de una entidad trae todos los tipos TEXTO' (($r.json.types -join ',') -eq 'TEXTO,TEXTO,TEXTO,TEXTO') ($r.json.types -join ',')
    $r = Exec 'DESC'
    Check 'la lista de entidades tipa CAMPOS como NUMERO' (($r.json.types -join ',') -eq 'TEXTO,TEXTO,NUMERO') ($r.json.types -join ',')

    # --- comentarios ---
    # El conteo de empleados no se asume: a esta altura del script ya se insertaron filas, asi que se
    # lee el valor real y se compara contra el mismo SELECT con y sin comentarios.
    $r = Exec "SELECT count(e) FROM Empleado e"
    $empleados = $r.json.rows[0][0]
    Check 'el conteo de empleados para los tests de comentarios' ($empleados -ge 1) $empleados

    $r = Exec "// un comentario`nSELECT count(e) FROM Empleado e"
    Check 'un comentario // arriba no rompe la sentencia' ($r.status -eq 200 -and $r.json.rows[0][0] -eq $empleados) $r.raw

    $r = Exec "# otro comentario`nSELECT count(e) FROM Empleado e"
    Check 'un comentario # arriba no rompe la sentencia' ($r.status -eq 200 -and $r.json.rows[0][0] -eq $empleados) $r.raw

    $r = Exec "-- comentario SQL`nSELECT count(e) FROM Empleado e"
    Check 'un comentario -- arriba no rompe la sentencia' ($r.status -eq 200 -and $r.json.rows[0][0] -eq $empleados) $r.raw

    $r = Exec "SELECT e.id, // el id`n e.nombre FROM Empleado e WHERE e.id = 1"
    Check 'un comentario en el medio de la sentencia se ignora' ($r.status -eq 200 -and $r.json.rowCount -eq 1) $r.raw

    # El caso que rompia: un ';' adentro de un comentario partia la sentencia.
    $r = Exec "SELECT e.nombre FROM Empleado e -- ojo; esto no corta`nWHERE e.id = 1"
    Check 'un ; adentro de un comentario no parte la sentencia' ($r.status -eq 200 -and $r.json.rowCount -eq 1) $r.raw

    # Una palabra clave adentro de un comentario no es la clausula.
    $r = Exec "INSERT INTO Libro li VALUES li.titulo='Con comentario' -- where no es where`n, li.precio=1"
    Check 'un where adentro de un comentario no es la clausula del UPDATE' ($r.status -eq 200 -and $r.json.affectedRows -eq 1) $r.raw

    # Un comentario adentro de un literal es texto, no comentario.
    $r = Exec "INSERT INTO Libro li VALUES li.titulo='texto -- no es comentario', li.precio=1"
    Check 'un -- adentro de un literal es texto' ($r.status -eq 200) $r.raw
    $r = Exec "SELECT l.titulo FROM Libro l WHERE l.titulo = 'texto -- no es comentario'"
    Check 'el texto con -- adentro se guardo entero' ($r.json.rowCount -eq 1 -and $r.json.rows[0][0] -eq 'texto -- no es comentario') ($r.json.rows[0] -join '|')

    $r = Exec "// solo un comentario"
    Check 'una sentencia que es solo un comentario avisa' ($r.status -eq 400 -and $r.json.error -match 'comentarios') $r.raw

    $r = Exec "SELECT count(e) FROM Empleado e // cierre"
    Check 'un comentario al final tampoco molesta' ($r.status -eq 200 -and $r.json.rows[0][0] -eq $empleados) $r.raw

    # --- el orden por click en el header ---
    Check 'la pagina trae el orden por click en el header' `
          ($page.Content -match 'function ordenarPor' -and $page.Content -match 'function compararCeldas' -and $page.Content -match "addEventListener\('click', function\(\) \{\s*ordenarPor") 'falta el orden por header'
    Check 'el orden usa el tipo que manda el backend' ($page.Content -match 'function tipoDeColumna' -and $page.Content -match 'datos\.types') 'no se usan los tipos del backend'
    Check 'el header muestra la flecha y el estado' ($page.Content -match 'th\.orden-asc::after' -and $page.Content -match 'aria-sort') 'falta el indicador de orden'
    Check 'ordenar guarda las filas originales (no las pisa)' ($page.Content -match 'tabla\.__filas' -and $page.Content -match 'function ordenarFilas') 'no se guardan las filas originales'
    Check 'ordenar reengancha las filas clickeables' ($page.Content -match 'function recablearFilas' -and $page.Content -match 'recablearFilas\(tabla\)') 'las filas ordenadas pierden el click'

    # --- editor sin wrap (scroll horizontal) ---
    Check 'el textarea no envuelve las lineas largas' ($page.Content -match 'id="hql"[^>]*wrap="off"') 'falta wrap="off" en el textarea'
    Check 'el CSS del textarea scrollea en horizontal' ($page.Content -match 'wrap:off' -and $page.Content -match 'overflow-x:auto') 'falta el scroll horizontal'

    # --- el parrafo ejecutado queda seleccionado ---
    Check 'al ejecutar se pinta el parrafo que corrio' ($page.Content -match 'function pintarRango' -and $page.Content -match 'ta\.setSelectionRange\(rango\.inicio') 'no se pinta el parrafo'
    Check 'pintarRango no pisa una seleccion del usuario' ($page.Content -match 'pintar: false' -and $page.Content -match 'pintar: true') 'no se distingue seleccion de parrafo'
    Check 'el rango del parrafo viaja con el texto a ejecutar' ($page.Content -match 'inicio: parrafo\.inicio, fin: parrafo\.fin') 'el rango no se propaga'

    # --- acciones visibles en cada entidad HQL ---
    Check 'la pagina no conserva el menu flotante' ($page.Content -notmatch 'id="menu"' -and $page.Content -notmatch 'function abrirMenu') 'quedo codigo del menu'
    Check 'cada entidad tiene iconos SVG de query e insert a la derecha' `
          ($page.Content -match 'const ICONO_QUERY = .+<svg' -and $page.Content -match 'const ICONO_INSERT = .+<svg' `
           -and $page.Content -match "query\.innerHTML = ICONO_QUERY" -and $page.Content -match "insert\.innerHTML = ICONO_INSERT" `
           -and $page.Content -match 'entidad-accion-query \{ color:#2196F3' -and $page.Content -match 'entidad-accion-insert \{ color:#4CAF50') 'faltan iconos SVG'
    Check 'SQL limpia las acciones HQL y no las vuelve a pintar' `
          ($page.Content -match "if \(languageActiva === 'sql'\) \{ return; \}" -and $page.Content -match "languageActiva === 'sql'\) \{ listaEntidades\.textContent = ''; asegurarTablas\(\); \}" `
           -and $page.Content -match "function pintarTablas\(\) \{ if\(languageActiva!=='sql'\)\{return;\}") 'SQL puede mostrar acciones HQL'
    Check 'los iconos tienen tamaño uniforme y etiqueta accesible' `
          ($page.Content -match 'width:20px; height:20px' -and $page.Content -match 'svg \{ width:18px; height:18px' `
           -and $page.Content -match "query\.setAttribute\('aria-label'" -and $page.Content -match "insert\.setAttribute\('aria-label'") 'los iconos no son accesibles o uniformes'
    Check 'nombres HQL y tablas SQL tienen el mismo hover visual' `
          ($page.Content -match 'entidad-nombre:hover, \.entidad-nombre:focus-visible,' -and $page.Content -match 'button\.entidad-item:hover, button\.entidad-item:focus-visible \{ background:#fff; filter:brightness\(\.82\); \}') 'falta el hover de nombres'
    Check 'el click en el nombre de la entidad sigue haciendo el DESC' `
          ($page.Content -match "boton\.addEventListener\('click', function\(\) \{ abrirEntidad\(nombre\)") 'el click dejo de hacer el DESC'
    Check 'query ejecuta SELECT limitado sin pisar el editor' `
          ($page.Content -match 'function ejecutarConsultaEntidad' -and $page.Content -match "ejecutarTexto\(selectDeEntidad\(entidad\)") 'query no ejecuta el SELECT limitado'
    Check 'insert genera la sentencia sin ejecutarla' `
          ($page.Content -match 'async function generarInsertEntidad' -and $page.Content -match "insertarEnEditor\(insertDeEntidad\(entidad") 'insert no escribe la sentencia'
    Check 'el INSERT se inserta sin borrar lo escrito' `
          ($page.Content -match 'function insertarEnParrafo' -and $page.Content -notmatch 'ta\.value = sentencia') 'el INSERT pisa el editor'
    Check 'el INSERT queda seleccionado al insertarlo' `
          ($page.Content -match 'ta\.setSelectionRange\(puesto\.seleccion\.inicio, puesto\.seleccion\.fin\)') 'no se selecciona lo insertado'
    Check 'el INSERT conserva el scroll del editor' `
          ($page.Content -match 'const scrollArriba = ta\.scrollTop' -and $page.Content -match 'ta\.scrollTop = scrollArriba' -and $page.Content -match 'ta\.scrollLeft = scrollIzquierda') 'el scroll del editor salta al final'
    Check 'query usa SELECT con LIMIT' ($page.Content -match 'function selectDeEntidad' -and $page.Content -match 'LIMITE_QUERY = 100' -and $page.Content -match "ejecutarTexto\(selectDeEntidad") 'query no limita'
    # El header no puede cambiar de tamano al pasar el mouse NI al cambiar de glyph: el indicador se
    # reserva siempre y con ancho fijo (⇅ no mide lo mismo que ↑ ni que ↓ en monoespaciada).
    $reserva = $page.Content.IndexOf("th.ordenable::after { content:'\21C5'")
    $hoverConGlyph = $page.Content.IndexOf("th.ordenable:hover::after { content:")
    Check 'el indicador de orden no cambia el tamano del header' `
          ($reserva -ge 0 -and $hoverConGlyph -lt 0) "reserva=$reserva hoverConGlyph=$hoverConGlyph"
    Check 'los tres glyphs del indicador tienen el mismo ancho' `
          ($page.Content -match 'display:inline-block; width:1em') 'el indicador no tiene ancho fijo'
    # --- tope de filas ---
    if ($MaxRows -lt 6) {
        $r = Exec 'SELECT e.id FROM Empleado e'
        Check 'el tope de filas trunca el resultado' ($r.json.truncated -eq $true -and $r.json.rowCount -eq $MaxRows) "truncated=$($r.json.truncated) rowCount=$($r.json.rowCount)"

        # Un LIMIT mas grande que el tope lo recorta el tope, y eso si es una truncacion: se avisa.
        $r = Exec 'from Empleado limit 100'
        Check 'un LIMIT mayor que el tope queda recortado por el tope' `
              ($r.json.truncated -eq $true -and $r.json.rowCount -eq $MaxRows) "truncated=$($r.json.truncated) rowCount=$($r.json.rowCount)"
        Check 'y el aviso dice que el tope lo recorto' ($r.json.message -match 'recortado') $r.json.message

        # Un LIMIT que entra en el tope no es una truncacion: no se avisa.
        $r = Exec 'from Empleado limit 2'
        Check 'un LIMIT que entra en el tope no se marca como truncado' ($r.json.truncated -eq $false) "truncated=$($r.json.truncated)"

        # El dry-run de un UPDATE sin WHERE tambien avisa del tope: es justo el caso donde el numero
        # evita que alguien toque toda la tabla creyendo que toca una fila.
        $r = Exec 'UPDATE Libro li SET li.disponible=true' $true
        Check 'el dry-run avisa cuando el tope trunco' ($r.json.truncated -eq $true -and $r.json.affectedRows -eq $MaxRows) "truncated=$($r.json.truncated) filas=$($r.json.affectedRows)"
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
