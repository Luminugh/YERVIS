"""F4.10: authored synthetic goldens; stdlib only, no application/fake execution.

Only mechanical UTF-16 offsets, source hashes and transport envelopes are generated.
Semantic labels, temporal answers and comparator expectations below are authored.
Run from any directory; --check compares bytes without changing files.
"""
import copy
import hashlib
import json
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'app/src/test/resources/agenda/v1'
SCENARIOS = []
FORBIDDEN = ['AUTO_CONFIRM', 'AUTO_MERGE', 'SCHEDULE_ALARM', 'WRITE_ORIGINAL',
             'INFER_MISSED_FROM_ABSENCE', 'SEND_CLOUD']


def utf16(text):
    return len(text.encode('utf-16-le')) // 2


def source(text, when='2026-10-15T17:00:00Z', zone='America/Lima', revision=None):
    n = len(SCENARIOS) + 1
    return dict(sourceEntryId=f's-{n:02d}', text=text,
                capturedAtEpochMillis=str(int(datetime.fromisoformat(when.replace('Z', '+00:00')).timestamp() * 1000)),
                timezoneId=zone, sourceType='TEXT',
                revision={'kind': 'ORIGINAL'} if revision is None else {'kind': 'CORRECTION', 'correctionId': str(revision)}) | (
                    {} if revision is None else {'originalText': '📝 Ayer envié el resumen inicial.'})


def evidence(s, quote):
    start = s['text'].index(quote)
    return dict(sourceEntryId=s['sourceEntryId'], revision=s['revision'], quote=quote,
                range=dict(startUtf16=utf16(s['text'][:start]), endUtf16=utf16(s['text'][:start + len(quote)])))


def field(s, value, quote=None, modality='ASSERTED', polarity='AFFIRMED', inferred=False, extra=()):
    assessment = dict(basis='INFERRED' if inferred else 'EXPLICIT', modality=modality,
                      polarity=polarity, certaintyValidation='UNVALIDATED')
    if inferred:
        assessment['inferenceExplanation'] = 'Hipótesis acotada a las observaciones citadas; requiere revisión.'
    return dict(value=value, evidence=[evidence(s, quote or s['text'])] + list(extra), assessment=assessment)


def period(start, to=None):
    return {'from': start} | ({'to': to} if to is not None else {})


def temporal(s, expression):
    return dict(expression=field(s, expression, expression),
                anchor=dict(sourceEntryId=s['sourceEntryId'], revision=s['revision']),
                precision='UNSPECIFIED', uncertainty='UNRESOLVED', resolved=None)


def proposal(s, category, title=None, pid='p', **data):
    return dict(proposalId=pid, category=category, proposalStatus='PROPOSED',
                data=dict(title=title or field(s, s['text']), **data))


def fact(s, kind='OCCURRENCE', **data):
    return proposal(s, 'FACT', factKind=field(s, kind), attribution={'kind': 'CAPTURE_AUTHOR'}, **data)


def recurrence(s, days=('MO', 'WE'), frequency='WEEKLY', time=None):
    r = dict(frequency=field(s, frequency), weekdays=[field(s, day) for day in days])
    if time:
        r['times'] = [field(s, time, time)]
    return r


def step(sources, proposals, suffix='a'):
    n = len(SCENARIOS) + 1
    snapshots = []
    for s in sources:
        snapshots.append({k: v for k, v in s.items() if k not in ('text', 'originalText')} | dict(
            textSha256=hashlib.sha256(s['text'].encode()).hexdigest(), textLengthUtf16=utf16(s['text']),
            authorizedRanges=[dict(startUtf16=0, endUtf16=utf16(s['text']))]))
    return dict(schemaVersion='1.0.0', requestId=f'r-{n:02d}-{suffix}', extractionId=f'e-{n:02d}-{suffix}',
                offsetUnit='UTF16_CODE_UNIT', sourceSnapshots=snapshots,
                outcome='COMPLETE' if proposals else 'NO_PROPOSALS', proposals=proposals, issues=[])


def expect(expression, status='RESOLVED', date=None, reason=None, **values):
    return dict(expression=expression, status=status, localDate=date, **({'reason': reason} if reason else {}), **values)


def add(name, lane, sources, proposals, reason, times=(), ambiguity=(), sequence=None,
        documents=None, comparisons=(), relations=(), unknown_dates=()):
    n = len(SCENARIOS) + 1
    docs = documents or [step(sources, proposals)]
    SCENARIOS.append(dict(id=f'agenda-{n:02d}', name=name, lane=lane, sources=sources,
        goldenDocuments=docs, expectedTemporal=list(times), comparisons=list(comparisons),
        expectedRelations=list(relations), unknownDates=list(unknown_dates), ambiguity=list(ambiguity),
        sequence=sequence or [], forbiddenActions=FORBIDDEN, rationale=reason,
        # Explicit semantic expectations live in the authored proposal fields, not fake output.
        evaluation=dict(componentChecks=True, providerEvaluation='NOT_RUN', humanReviewRequired=lane == 'C')))


# A: verifiable contracts and deterministic temporal/comparator behaviour, not extraction quality.
s = source('Ayer entregué el informe.'); add('Hecho anterior a captura', 'A', [s], [fact(s, occurredAt=temporal(s, 'Ayer'))], 'Ocurrió el día anterior; captura no equivale a ocurrencia.', [expect('Ayer', date='2026-10-14')])
s = source('Debo entregar el informe mañana.'); add('Tarea pendiente y vencimiento', 'A', [s], [proposal(s, 'TASK', state=field(s, 'PENDING'), dueAt=temporal(s, 'mañana'))], 'Una obligación afirmada no está completada.', [expect('mañana', date='2026-10-16')])
s = source('Ya terminé el informe ayer.'); add('Tarea completada', 'A', [s], [proposal(s, 'TASK', state=field(s, 'COMPLETED'))], 'No recrear una tarea pendiente a partir de una finalizada.')
s = source('Reunión el 2026-10-20 a las 15:00.'); add('Evento con fecha explícita', 'A', [s], [proposal(s, 'EVENT', state=field(s, 'SCHEDULED'), startsAt=temporal(s, '2026-10-20 a las 15:00'))], 'Propuesta de evento, sin alarma implícita.', [expect('2026-10-20 a las 15:00', date='2026-10-20', localTime='15:00')])
s = source('Cancelé la reunión.'); add('Evento cancelado', 'A', [s], [proposal(s, 'EVENT', state=field(s, 'CANCELLED'))], 'Cancelar no crea otro evento ni elimina el historial.')
s = source('No tengo reunión el viernes.'); add('Negación', 'A', [s], [proposal(s, 'EVENT', title=field(s, s['text'], polarity='NEGATED'), state=field(s, 'UNKNOWN', polarity='NEGATED'), startsAt=temporal(s, 'el viernes'))], 'Negación no autoriza evento confirmado.', [expect('el viernes', 'AMBIGUOUS', reason='WEEKDAY_NEEDS_REFERENCE')])
s = source('Quizá tenga reunión mañana.'); add('Hipótesis', 'A', [s], [proposal(s, 'EVENT', title=field(s, s['text'], modality='POSSIBLE'), state=field(s, 'TENTATIVE', modality='POSSIBLE'), startsAt=temporal(s, 'mañana'))], 'Resolver mañana no confirma un compromiso.', [expect('mañana', date='2026-10-16')])
s = source('Quiero leer cada día.'); add('Hábito deseado', 'B', [s], [proposal(s, 'HABIT', title=field(s, s['text'], modality='DESIRED'), desiredFrequency=dict(frequency=field(s, 'DAILY')))], 'Deseo no prueba ninguna acción realizada.')
s = source('Los lunes y miércoles estudio a las 18:00.'); add('Rutina declarada semanal', 'A', [s], [proposal(s, 'ROUTINE', patternKind=field(s, 'DECLARED'), recurrence=recurrence(s, time='18:00'))], 'Patrón explícito, aún pendiente de aceptación.')
s = source('Entregué el informe; no recuerdo cuándo.'); add('Hecho sin fecha conocida', 'A', [s], [fact(s)], 'No utilizar captura como fecha del hecho.', ambiguity=['Fecha de ocurrencia desconocida'])
observed = [source(f'El {d} estudié a las 18:00.', f'{d}T23:10:00Z') for d in ['2026-10-05', '2026-10-12', '2026-10-19']]
for i, entry in enumerate(observed): entry['sourceEntryId'] += f'-{i}'
p = proposal(observed[0], 'ROUTINE', title=field(observed[0], 'Estudio en las fechas registradas', modality='UNCERTAIN', inferred=True, extra=[evidence(e, e['text']) for e in observed[1:]]),
    patternKind=field(observed[0], 'OBSERVED', modality='UNCERTAIN', inferred=True, extra=[evidence(e, e['text']) for e in observed[1:]]),
    observedPeriod=period(start=temporal(observed[0], '2026-10-05'), to=temporal(observed[-1], '2026-10-19')),
    occurrences=[dict(occurrenceId=f'o{i}', at=temporal(e, d), state=field(e, 'COMPLETED')) for i, (e, d) in enumerate(zip(observed, ['2026-10-05', '2026-10-12', '2026-10-19']))])
add('Patrón observado en tres semanas', 'B', observed, [p], 'Repeticiones sustentan hipótesis acotada, no rutina confirmada.', sequence=['2026-10-05', '2026-10-12', '2026-10-19'])
s = source('Recuérdame llamar mañana.'); add('Recordatorio explícito', 'A', [s], [proposal(s, 'TASK', field(s, 'llamar', 'llamar'), 'llamar'), proposal(s, 'REMINDER', target=field(s, dict(proposalId='llamar'), 'llamar'), request=field(s, 'REQUESTED', 'Recuérdame'), trigger=dict(kind='AT', at=temporal(s, 'mañana')))], 'Solicitud explícita independiente de ejecutar una alarma.', [expect('mañana', date='2026-10-16')])
for zone, date in [('America/Lima', '2026-10-16'), ('America/New_York', '2026-10-17'), ('Europe/Madrid', '2026-10-17')]:
    s = source('Mañana presento el avance.', '2026-10-16T04:30:00Z', zone)
    add('Cambio de día en ' + zone, 'A', [s], [proposal(s, 'EVENT', startsAt=temporal(s, 'Mañana'))], 'Mismo instante UTC, día local de captura distinto.', [expect('Mañana', date=date)])
for when, expr, date, name in [('2026-10-31T17:00:00Z', 'mañana', '2026-11-01', 'Fin de mes'), ('2026-12-31T17:00:00Z', 'mañana', '2027-01-01', 'Fin de año'), ('2024-02-28T17:00:00Z', 'mañana', '2024-02-29', 'Año bisiesto')]:
    s = source('La entrega es ' + expr + '.', when); add(name, 'A', [s], [proposal(s, 'TASK', dueAt=temporal(s, expr))], 'Aritmética de calendario anclada a captura histórica.', [expect(expr, date=date)])
for zone, expr, status, reason in [('America/New_York', '2026-03-08 02:30', 'INVALID', 'DST_GAP'), ('America/New_York', '2026-11-01 01:30', 'AMBIGUOUS', 'DST_OVERLAP'), ('Europe/Madrid', '2026-03-29 02:30', 'INVALID', 'DST_GAP'), ('Europe/Madrid', '2026-10-25 02:30', 'AMBIGUOUS', 'DST_OVERLAP')]:
    s = source('Cita ' + expr + '.', zone=zone); add(reason + ' ' + zone, 'A', [s], [proposal(s, 'EVENT', startsAt=temporal(s, expr))], 'No elegir automáticamente un instante inexistente o duplicado.', [expect(expr, status, date=expr[:10], reason=reason)])
for expr, status, reason, values in [('5 de marzo', 'PARTIALLY_RESOLVED', 'YEAR_NOT_STATED', dict(monthDayWithoutYear='03-05')), ('el próximo viernes', 'AMBIGUOUS', 'NEXT_WEEKDAY_NEEDS_POLICY', {}), ('a fin de mes', 'AMBIGUOUS', 'MONTH_BOUNDARY_UNDEFINED', {}), ('en la tarde', 'PARTIALLY_RESOLVED', 'DAY_PART_HAS_NO_CLOCK_TIME', dict(dayPart='AFTERNOON')), ('a las nueve', 'AMBIGUOUS', 'AMBIGUOUS_HOUR', {})]:
    s = source('Reunión ' + expr + '.'); add('Expresión ' + expr, 'C', [s], [proposal(s, 'EVENT', startsAt=temporal(s, expr))], 'Conservar incertidumbre; no imponer año, política u hora.', [expect(expr, status, reason=reason, **values)], ambiguity=[reason])
s = source('Prepararé el informe hoy; vence pasado mañana.'); add('Planificación distinta de vencimiento', 'A', [s], [proposal(s, 'TASK', plannedAt=temporal(s, 'hoy'), dueAt=temporal(s, 'pasado mañana'))], 'Dos roles temporales diferentes.', [expect('hoy', date='2026-10-15'), expect('pasado mañana', date='2026-10-17')])
s = source('Cita mañana a las 15:00. Recuérdame mañana a las 14:00.'); event = proposal(s, 'EVENT', pid='cita', startsAt=temporal(s, 'mañana a las 15:00')); reminder = proposal(s, 'REMINDER', pid='aviso', request=field(s, 'REQUESTED', 'Recuérdame'), target=field(s, dict(proposalId='cita'), 'Cita'), trigger=dict(kind='AT', at=temporal(s, 'mañana a las 14:00')))
add('Inicio de evento y aviso distinto', 'A', [s], [event, reminder], 'Aviso separado y pendiente de autorización.', [expect('mañana a las 15:00', date='2026-10-16', localTime='15:00'), expect('mañana a las 14:00', date='2026-10-16', localTime='14:00')])
s = source('📝 Ayer envié el resumen corregido.', revision=17); s['sourceType'] = 'VOICE'; add('Revisión y emoji UTF-16', 'A', [s], [fact(s, occurredAt=temporal(s, 'Ayer'))], 'Evidencia en revisión 17, emoji ocupa dos unidades.', [expect('Ayer', date='2026-10-14')])
s = source('Los lunes estudio.'); p = proposal(s, 'ROUTINE', patternKind=field(s, 'DECLARED'), recurrence=recurrence(s, days=('MO',))); docs = [step([s], [p], 'a'), step([s], [copy.deepcopy(p)], 'b')]
add('Reextracción con otro transporte', 'A', [s], [p], 'Misma fuente/revisión/contenido; cambia solicitud/extracción.', documents=docs, comparisons=[dict(leftDocument=0, rightDocument=1, left='p', right='p', expected='TECHNICAL_DUPLICATE', temporalUncertainty=False)])
s = source('Reunión el 2026-10-20. Otra reunión el 2026-10-21.'); p1 = proposal(s, 'EVENT', field(s, 'Reunión', 'Reunión'), 'antes', startsAt=temporal(s, '2026-10-20')); p2 = proposal(s, 'EVENT', field(s, 'Reunión', 'reunión'), 'despues', startsAt=temporal(s, '2026-10-21'))
add('Homónimos con fechas distintas', 'A', [s], [p1,p2], 'Título igual no basta para deduplicar.', comparisons=[dict(leftDocument=0,rightDocument=0,left='antes',right='despues',expected='DISTINCT',temporalUncertainty=False)])


def linked_change(name, category, old_text, new_text, kind, expected, old_data, new_data, lane='A'):
    a = source(old_text, '2026-10-05T17:00:00Z'); a['sourceEntryId'] += '-old'
    b = source(new_text, '2026-10-19T17:00:00Z'); b['sourceEntryId'] += '-new'
    old = proposal(a, category, pid='antes', **old_data(a)); new = proposal(b, category, pid='nuevo', **new_data(b))
    if 'Quizá' in b['text']:
        new['data']['title'] = field(b, b['text'], modality='POSSIBLE')
    new['relations'] = [field(b, dict(kind=kind, targetProposalId='antes'))]
    add(name,lane,[a,b],[old,new], 'Vínculo anterior→nuevo; conserva fuentes/versiones, no cambia estados automáticamente.',
        comparisons=[dict(leftDocument=0,rightDocument=0,left='antes',right='nuevo',expected=expected,temporalUncertainty=False)],
        relations=[dict(source='nuevo',target='antes',kind=kind)], sequence=['2026-10-05','2026-10-19'])


linked_change('Cambio de horario de rutina', 'ROUTINE', 'Los lunes estudio a las 18:00.', 'Desde ahora los lunes estudio a las 19:00.', 'UPDATES','UPDATE_PROPOSED', lambda s: dict(patternKind=field(s,'DECLARED'),recurrence=recurrence(s,('MO',),time='18:00')), lambda s: dict(patternKind=field(s,'DECLARED'),recurrence=recurrence(s,('MO',),time='19:00')))
linked_change('Excepción semanal', 'ROUTINE', 'Los lunes estudio.', 'El 2026-10-26 no estudio; es una excepción.', 'UPDATES','EXCEPTION_PROPOSED', lambda s: dict(patternKind=field(s,'DECLARED'),recurrence=recurrence(s,('MO',))), lambda s: dict(patternKind=field(s,'DECLARED'),exceptions=[dict(exceptionId='ex1',period=period(start=temporal(s,'2026-10-26'),to=temporal(s,'2026-10-26')),effect=field(s,'CANCELLED_OCCURRENCE'),description=field(s,s['text']))]))
# B: independently authored multiweek answers reserved for a future extractor, not fake success.
entries = [source(f'El {d} caminé en el parque.', f'{d}T17:00:00Z') for d in ['2026-09-07','2026-09-14','2026-09-28']]
for i,e in enumerate(entries): e['sourceEntryId'] += f'-{i}'
add('Repeticiones sin declarar rutina', 'B', entries, [fact(e, occurredAt=temporal(e,d)) | dict(proposalId=f'hecho{i}') for i,(e,d) in enumerate(zip(entries,['2026-09-07','2026-09-14','2026-09-28']))], 'Tres hechos no prueban rutina confirmada; patrón sería hipótesis adicional revisable.', sequence=['2026-09-07','2026-09-14','2026-09-28'], ambiguity=['Patrón opcional, no confirmado'])
s = source('Los lunes estudio; completé el 2026-10-05 y el 2026-10-19.', '2026-10-20T17:00:00Z'); p=proposal(s,'ROUTINE',patternKind=field(s,'DECLARED'),recurrence=recurrence(s,('MO',)),occurrences=[dict(occurrenceId=f'o{i}',at=temporal(s,d),state=field(s,'COMPLETED')) for i,d in enumerate(['2026-10-05','2026-10-19'])]); add('Semana sin notas', 'B',[s],[p],'El 12 de octubre está desconocido, no omitido ni incumplido.',sequence=['2026-10-05','2026-10-12','2026-10-19'],unknown_dates=['2026-10-12'])
s=source('Los lunes estudio. El 2026-10-05 completé; el 2026-10-12 cancelé; el 2026-10-19 pospuse.', '2026-10-20T17:00:00Z'); p=proposal(s,'ROUTINE',patternKind=field(s,'DECLARED'),recurrence=recurrence(s,('MO',)),occurrences=[dict(occurrenceId=f'o{i}',at=temporal(s,d),state=field(s,state)) for i,(d,state) in enumerate([('2026-10-05','COMPLETED'),('2026-10-12','CANCELLED'),('2026-10-19','POSTPONED')])]); add('Ocurrencias distintas', 'B',[s],[p],'Una plantilla, tres ocurrencias explícitas con estados diferentes.',sequence=['2026-10-05','2026-10-12','2026-10-19'])
linked_change('Interrupción de rutina', 'ROUTINE','Los lunes estudio.','Pauso el estudio del 20 al 22 de octubre de 2026.','UPDATES','EXCEPTION_PROPOSED',lambda s:dict(patternKind=field(s,'DECLARED')),lambda s:dict(patternKind=field(s,'DECLARED'),exceptions=[dict(exceptionId='pausa',period=period(start=temporal(s,'del 20 al 22 de octubre de 2026')),effect=field(s,'PAUSED'),description=field(s,s['text']))]),'B')
a=source('Reunión de Atlas el 2026-10-20.'); a['sourceEntryId']+='-a'; b=source('La misma reunión de Atlas sigue el 2026-10-20.','2026-10-16T17:00:00Z'); b['sourceEntryId']+='-b'; add('Menciones múltiples mismo evento','B',[a,b],[proposal(a,'EVENT',pid='primero',startsAt=temporal(a,'2026-10-20')),proposal(b,'EVENT',pid='segundo',startsAt=temporal(b,'2026-10-20'))],'Candidato humano a equivalencia; el comparador conservador no fusiona fuentes distintas.',comparisons=[dict(leftDocument=0,rightDocument=0,left='primero',right='segundo',expected='DISTINCT',temporalUncertainty=False)],ambiguity=['Posible equivalencia requiere revisión'])
entries=[source(t,w) for t,w in [('Debo preparar el informe.','2026-10-05T17:00:00Z'),('El informe está bloqueado por una dependencia.','2026-10-12T17:00:00Z'),('Ya terminé el informe.','2026-10-19T17:00:00Z')]]
for i,e in enumerate(entries): e['sourceEntryId']+=f'-{i}'
ps=[proposal(e,'TASK',pid=f't{i}',state=field(e,state)) for i,(e,state) in enumerate(zip(entries,['PENDING','BLOCKED','COMPLETED']))]
for i in [1,2]: ps[i]['relations']=[field(entries[i],dict(kind='UPDATES',targetProposalId=f't{i-1}'))]
add('Historial de tarea pendiente bloqueada completada','B',entries,ps,'Tres propuestas versionables; ninguna aceptación ni reemplazo automático.',sequence=['2026-10-05','2026-10-12','2026-10-19'],relations=[dict(source='t1',target='t0',kind='UPDATES'),dict(source='t2',target='t1',kind='UPDATES')])
linked_change('Contradicción posterior','FACT','El 2026-10-01 trabajé por la mañana.','Corrijo: el 2026-10-01 trabajé por la tarde, no por la mañana.','CONTRADICTS','CONFLICT_REQUIRES_REVIEW',lambda s:dict(factKind=field(s,'ASSERTION'),attribution={'kind':'CAPTURE_AUTHOR'}),lambda s:dict(factKind=field(s,'ASSERTION'),attribution={'kind':'CAPTURE_AUTHOR'}),'B')
linked_change('Cambio tentativo de plan','EVENT','Reunión el 2026-10-20.','Quizá cambiemos al 2026-10-21.','CONTRADICTS','CONFLICT_REQUIRES_REVIEW',lambda s:dict(startsAt=temporal(s,'2026-10-20')),lambda s:dict(startsAt=temporal(s,'2026-10-21')),'C')
s=source('Ana del curso y Ana del trabajo son personas diferentes.'); add('Personas homónimas','C',[s],[proposal(s,'PERSON',field(s,'Ana del curso','Ana del curso'),'curso',context=field(s,'curso','curso')),proposal(s,'PERSON',field(s,'Ana del trabajo','Ana del trabajo'),'trabajo',context=field(s,'trabajo','trabajo'))],'No fusionar por nombre.',ambiguity=['Identidad requiere contexto y revisión'])
s=source('Creo que Ana prefiere resúmenes en las reuniones del curso.'); person=proposal(s,'PERSON',field(s,'Ana','Ana'),'ana'); pref=fact(s,'PREFERENCE',subject=field(s,dict(proposalId='ana'),'Ana'),scope=field(s,'reuniones del curso','reuniones del curso')); pref['proposalId']='preferencia'; pref['data']['title']=field(s,s['text'],modality='UNCERTAIN'); add('Preferencia atribuida sin perfil permanente','C',[s],[person,pref],'Observación incierta, no diagnóstico ni personalidad estable.',ambiguity=['Preferencia contextual no probada'])
s=source('Mi idea es una agenda. Decidí usar texto primero. Quiero terminar el prototipo.'); add('Idea decisión objetivo','B',[s],[proposal(s,'IDEA',field(s,'una agenda','Mi idea es una agenda'),'idea'),proposal(s,'DECISION',field(s,'usar texto primero','Decidí usar texto primero'),'decision'),proposal(s,'GOAL',field(s,'terminar el prototipo','Quiero terminar el prototipo',modality='DESIRED'),'meta')],'Deseo, decisión y propuesta creativa son conceptos distintos.')
s=source('Ana participa en Atlas. Ana debe preparar el informe de Atlas.'); add('Persona proyecto y tarea vinculados','B',[s],[proposal(s,'PERSON',field(s,'Ana','Ana'),'ana'),proposal(s,'PROJECT',field(s,'Atlas','Atlas'),'atlas',participants=[field(s,dict(proposalId='ana'),'Ana participa en Atlas')]),proposal(s,'TASK',field(s,'preparar el informe de Atlas','preparar el informe de Atlas'),pid='tarea',project=field(s,dict(proposalId='atlas'),'informe de Atlas'),actor=field(s,dict(proposalId='ana'),'Ana debe preparar'))],'Participante y responsable expresos; enlaces a persona/proyecto con evidencia.')
s=source('Tengo cita mañana. No quiero avisos.'); add('Fecha sin solicitud de alarma','B',[s],[proposal(s,'EVENT',startsAt=temporal(s,'mañana'))],'No hay REMINDER; mención temporal y negación de avisos no autorizan alarma.',[expect('mañana',date='2026-10-16')])
s=source('Quiero caminar cada día. Ayer caminé.'); add('Hábito deseado y hecho realizado','B',[s],[proposal(s,'HABIT',field(s,'caminar cada día','Quiero caminar cada día',modality='DESIRED'),'habito',desiredFrequency=dict(frequency=field(s,'DAILY','cada día'))),fact(s,title=field(s,'Ayer caminé','Ayer caminé'),occurredAt=temporal(s,'Ayer'))],'Una ocurrencia no demuestra adherencia diaria.',[expect('Ayer',date='2026-10-14')])


# Additional insufficient-evidence variant, not a 49th principal scenario.
unknown = source('Ella lo hizo.')
unknown['sourceEntryId'] = 's-43-unknown'
SCENARIOS[42]['sources'].append(unknown)
empty_document = step([unknown], [], 'b')
empty_document['requestId'] = 'r-43-b'
empty_document['extractionId'] = 'e-43-b'
empty_document['issues'] = [dict(code='INSUFFICIENT_EVIDENCE',
    message='No identifica persona ni acción verificable.', affectedProposalIds=[],
    evidence=[evidence(unknown, unknown['text'])])]
SCENARIOS[42]['goldenDocuments'].append(empty_document)
SCENARIOS[42]['ambiguity'].append('Ella no identifica una persona ni una acción verificable; variante sin propuestas.')


def main():
    assert len(SCENARIOS) == 48, len(SCENARIOS)
    # Explicit compatibility observations, distinct from semantic gold. These are NOT
    # approvals of the desired future behaviour and must remain visible in reports.
    SCENARIOS[26]['expectedTemporal'][0]['knownLimitation'] = dict(
        observedReason='UNSUPPORTED_EXPRESSION', desiredReason='AMBIGUOUS_HOUR',
        explanation='Hora sin fecha permanece ambigua; parser actual no reconoce esta forma aislada.')
    for i in [32, 33, 37]:
        SCENARIOS[i]['comparisons'][0]['knownLimitation'] = dict(
            observedKind='CONFLICT_REQUIRES_REVIEW',
            explanation='patternKind compara también evidencia de otra fuente; conserva conflicto revisable en vez del subtipo esperado.')
    files = {}
    for item in SCENARIOS:
        files[item['id'] + '.json'] = (json.dumps(item, ensure_ascii=False, indent=2) + '\n').encode()
    index = dict(corpusVersion='1.0.0', schemaVersion='1.0.0', syntheticOnly=True,
                 expectedOrigin='MANUALLY_AUTHORED_NOT_FAKE',
                 scenarios=[dict(id=s['id'],file=s['id']+'.json',lane=s['lane'],sha256=hashlib.sha256(files[s['id']+'.json']).hexdigest()) for s in SCENARIOS])
    files['index.json'] = (json.dumps(index, ensure_ascii=False, indent=2) + '\n').encode()
    if '--check' in sys.argv:
        for name,data in files.items():
            assert (OUT/name).read_bytes() == data, name
        assert {p.name for p in OUT.glob('*.json')} == set(files)
        print(f'{len(SCENARIOS)} scenarios / {len(files)} JSON byte-identical')
    else:
        OUT.mkdir(parents=True, exist_ok=True)
        for name,data in files.items(): (OUT/name).write_bytes(data)
        print(f'Wrote {len(files)} synthetic JSON files')


if __name__ == '__main__': main()
