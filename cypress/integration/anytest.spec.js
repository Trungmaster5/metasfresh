const add = (a, b) => a + b;
it('adds numbers', () => {
  cy.wrap(add(2, 3)).snapshot();
  cy.wrap(add(1, 100)).snapshot();
  cy.wrap(add(-6, -3)).snapshot({ name: 'negatives' });
});
