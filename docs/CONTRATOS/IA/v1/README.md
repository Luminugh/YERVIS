# Propuestas IA — contrato 1.0.0

Este directorio contiene el schema de intercambio de [F4.02](../../../IMPLEMENTACIONES/F4.02-esquema-propuestas.md). No contiene schemas Room ni código que guarde, interprete o envíe datos.

- `proposals.schema.json`: dialecto JSON Schema 2020-12, ID `urn:yervis:ai:proposals:1.0.0`. Referencias `$ref` internas, once categorías y objetos cerrados.
- `fixtures/valid/`: 14 respuestas sintéticas coherentes con las reglas del contrato, incluidas conclusiones personales acotadas y hechos pasados.
- `fixtures/invalid/`: 12 rechazos estructurales (`s*`) y 10 negativos semánticos (`k*`). Los `k*` **pasan Schema por diseño**; requieren las validaciones futuras Kotlin F4.04. No usarlos como salidas aceptables del fake.
- `fixtures/sources.json`: fuentes sintéticas/versiones/ventanas y correlaciones del host para cotejar los ejemplos. Es soporte local de pruebas, no contrato definitivo de petición ni instrucciones de envío cloud. No sustituir con recuerdos reales.
- `fixtures/manifest.json`: expectativa por archivo, con motivo de rechazo o gate semántico pendiente.
- `fixtures/validation-report.json`: resultado de la ejecución de escritorio, herramienta, hash del schema y expectativas comprobadas. `semanticGateExecuted: false` y `productionKotlinValidationImplemented: false` son deliberados.

Los originales y los correctionId de la app mantienen su contrato Kotlin. Solo el transporte nuevo usa cadenas decimales para los Long; no convertir unidades ni escribir una migración Room a partir de estos JSON. Los offsets son UTF-16, no la longitud Unicode que usa `maxLength` del schema. Una cita que aparece en el recuerdo pero fuera del fragmento autorizado no es válida.

## Reproducir la comprobación estructural

Desde la raíz Git, usar un Python de escritorio con `jsonschema==4.25.1` y soporte Draft 2020-12. La ejecución registrada usó Python 3.12.14 del runtime local de Codex; sus wheels de validación están aisladas en `.gradle/f402-tools` y el recibo de versiones/URLs/hashes en `.gradle/f402-tooling.json` (ambos ignorados por Git). Ninguna es dependencia Gradle, APK ni parte del runtime de voz. No se modificó el Python del sistema ni se ejecutaron scripts fuente remotos.

Este bloque reproduce parsing estricto, metaschema y expectativas estructurales; no es el validador de negocio de F4.04. Requiere las herramientas locales indicadas y no descarga nada:

```python
import json
import socket
import sys
from pathlib import Path

sys.path.insert(0, str(Path('.gradle/f402-tools').resolve()))
from jsonschema import Draft202012Validator, FormatChecker

def no_network(*args, **kwargs):
    raise AssertionError('La validación de fixtures no requiere red')
socket.socket.connect = no_network

def unique_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Clave duplicada: ' + key)
        result[key] = value
    return result

def invalid_number(value):
    raise ValueError('Número JSON no permitido: ' + value)

def load(path):
    return json.loads(path.read_text(encoding='utf-8'),
                      object_pairs_hook=unique_keys,
                      parse_constant=invalid_number)

root = Path('docs/CONTRATOS/IA/v1')
for path in root.rglob('*.json'):
    load(path)
schema = load(root / 'proposals.schema.json')
Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema, format_checker=FormatChecker())
cases = load(root / 'fixtures/manifest.json')['cases']
for case in cases:
    valid = validator.is_valid(load(root / case['file']))
    assert valid == case['expectedSchemaValid'], case['file']
print(f'{len(cases)} expectativas de Schema coinciden')
```

Además, para la autoría de los positivos se cotejaron snapshots del host, SHA-256, tamaño UTF-16, citas con `[inicio,fin)` y referencias internas contra `sources.json`. Se revisó cobertura de once categorías, refs de Schema locales, objetos cerrados y presupuestos de los ejemplos. Estas aserciones de fixtures no sustituyen parsing, autorización, validación temporal/semántica ni revisión de usuario en la app; los negativos `k*` aún no tienen validador Kotlin implementado.

No modificar el schema 1.0.0 al ampliar el contrato publicado. Crear otra versión/artefacto y añadir pruebas de negociación/rechazo explícitas. El consumidor futuro debe rechazar una versión desconocida; no ignorar campos ni activar acciones por una respuesta estructuralmente válida.
