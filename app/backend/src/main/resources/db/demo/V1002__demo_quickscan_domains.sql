-- Demo-data: de quickscan bevat de eerste drie kennisgebieden.
UPDATE domains SET in_quickscan = TRUE WHERE id IN (
  '10000000-0000-0000-0000-000000000001',
  '10000000-0000-0000-0000-000000000002',
  '10000000-0000-0000-0000-000000000003');
