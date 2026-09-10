"""Validate fixture integrity and manually authored golden totals, not production logic."""
import csv
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / 'samples' / 'recall-001'


def read(name, key):
    with (ROOT / name).open() as f:
        rows = list(csv.DictReader(f))
    result = {r[key]: r for r in rows}
    assert len(result) == len(rows), f'Duplicate {key}'
    return result


products = read('products.csv', 'product_id')
receipts = read('receipts.csv', 'receipt_id')
inventory = read('inventory.csv', 'inventory_id')
shipments = read('shipments.csv', 'shipment_id')
allocations = read('shipment_allocations.csv', 'allocation_id')
decisions = read('expected_receipt_decisions.csv', 'receipt_id')
impacts = read('expected_shipment_impacts.csv', 'shipment_id')
summary = json.loads((ROOT / 'expected_summary.json').read_text())
assert set(decisions) == set(receipts)
assert set(impacts) == set(shipments)
for r in receipts.values():
    assert r['product_id'] in products
    assert int(r['received_quantity']) >= 0
for r in shipments.values():
    assert r['product_id'] in products
    assert int(r['quantity']) >= 0
for r in inventory.values():
    assert r['receipt_id'] in receipts
    assert int(r['quantity']) >= 0
for a in allocations.values():
    assert a['shipment_id'] in shipments and a['receipt_id'] in receipts
    assert int(a['quantity']) > 0
    assert shipments[a['shipment_id']]['product_id'] == receipts[a['receipt_id']]['product_id']
for rid, r in receipts.items():
    stock = sum(int(i['quantity']) for i in inventory.values() if i['receipt_id'] == rid)
    shipped = sum(int(a['quantity']) for a in allocations.values() if a['receipt_id'] == rid)
    assert stock + shipped <= int(r['received_quantity'])
for phase in ('before', 'after'):
    stock_totals = dict.fromkeys(('TARGET', 'NON_TARGET', 'NEEDS_REVIEW'), 0)
    shipment_totals = stock_totals.copy()
    for i in inventory.values():
        stock_totals[decisions[i['receipt_id']][phase]] += int(i['quantity'])
    for sid, shipment in shipments.items():
        counts = dict.fromkeys(stock_totals, 0)
        allocated = 0
        for a in allocations.values():
            if a['shipment_id'] == sid:
                quantity = int(a['quantity'])
                counts[decisions[a['receipt_id']][phase]] += quantity
                allocated += quantity
        assert allocated <= int(shipment['quantity'])
        counts['NEEDS_REVIEW'] += int(shipment['quantity']) - allocated
        for state, count in counts.items():
            assert count == int(impacts[sid][f'{phase}_{state.lower()}']), (phase, sid, state)
            shipment_totals[state] += count
    assert stock_totals == summary[phase]['inventory']
    assert shipment_totals == summary[phase]['shipments']
print('PASS: IDs, references, quantity bounds, before/after golden totals (7 receipts, 4 shipments)')
