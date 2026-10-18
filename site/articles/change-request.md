# Revision packet: one change request

Revise the article and the LinkedIn post in this file. Then update the published surfaces so they match the revision:

- `site/articles/change-request.html`
- the `#change` section in `site/index.html`

Do not invent numbers, file paths, test names, or a multi-repository run. This published run is one configuration root.

## Voice

Direct and specific. Name the test, the file, and the number. Do not sell. Do not say the product "seamlessly" does anything. Keep the failure in the story: the run is FAIL.

## Locked facts

Source report: `site/demo/change/change-request.html`, run `20261009-115357-0b2948`, command `cherry-test --root app/shop`. Branch `discount` compared with `main`. Pull request: https://github.com/lmrrcc/shop/pull/1

Header: `cherry-test FAIL`. Packages 2/3. Tests run 106, skipped 0, failed 1, 99% ok. Coverage: all 92.8%, unit 97.9%, integration 88.0%. Diff coverage 721/776, 55 uncovered, 92.9%.

Packages:

- `shop-rs-api`: FAIL. Integration lines 476/526 (90.5%). Integration functions 113/137 (82.5%). Merged lines 476/526 (90.5%). Nine files in the changes section, 76% covered / 24% uncovered.
- `shop-ts-lib-cart`: ok. Unit lines 42/47 (89.4%). Unit functions 7/8 (87.5%). Integration lines 28/47 (59.6%). Integration functions 4/8 (50.0%). Merged lines 42/47 (89.4%). Two files, 84% covered / 16% uncovered.
- `shop-ts-web-react`: ok. Unit lines 189/189 (100%). Unit functions 17/17 (100%). Merged lines 203/203 (100%). Eight files, 100% covered / 0% uncovered.

The changes section lists 19 files for project `shop`, with the same 721/776 diff coverage, and the project fold is FAIL.

Files with added or removed lines include:

- `shop-rs-api/migrations/0002_product_discount.sql` +4 −0
- `shop-rs-api/src/application/tests.rs` +4 −2
- `shop-rs-api/src/domain/model.rs` +51 −1, 82% covered / 18% uncovered
- `shop-rs-api/src/infrastructure/postgres.rs` +20 −10, 100% / 0%
- `shop-rs-api/src/infrastructure/web.rs` +11 −1, 80% / 20%
- `shop-rs-api/tests/integration.rs` +21 −0
- `shop-ts-lib-cart/src/cart.test.ts` +12 −1
- `shop-ts-lib-cart/src/cart.ts` +16 −0, 84% / 16%
- `shop-ts-web-react/src/Shop.tsx` +4 −2, 100% / 0%
- `shop-ts-web-react/src/shop.test.tsx` +7 −6
- `shop-ts-web-react/src/shopApi.test.ts` +2 −2
- `shop-ts-web-react/src/shopApi.ts` +9 −1, 100% / 0%
- `shop-ts-web-react/tests/functional.spec.ts` +2 −2
- `shop-ts-web-react/tests/visual.spec.ts` +3 −3

Some listed files have +0 −0. Do not explain why unless you re-read the report.

Behavior under test: a stone mug is seeded at 10 percent off. Two mugs list at 1800 cents and check out at 1620 cents each, 3240 cents together.

`shop_project_checkout_suite_applies_the_mug_discount_test` passed in 9 ms. It asserts `unit_price_cents == 1620` and `total_cents == 3240`.

The only failure is `application::tests::catalog_service::reports_a_price_mismatch`. Assertion `left == right` failed: listed price did not match the expected 1500. Left was 1800, right was 1500. Its source file is in the diff.

Tests on this change: 13 recorded tests whose source file is in the diff. Twelve passed. The thirteenth is the price-mismatch failure. The twelve passing names are `adds_line_to_cart`, `rejects_quantity_beyond_stock`, `rejects_zero_quantity`, `unknown_cart_not_found`, `lists_seeded_products`, `rejects_invalid_sku`, `serves_openapi`, `unknown_sku_not_found`, `applies_the_mug_discount`, `places_order_and_decrements_stock`, `rejects_empty_cart`, `rejects_second_checkout`.

Selection rule, from `test/cherry-test/src/report/test_view.rs`: a recorded test is included when the file its snippet was read from matches a changed path. A changed path has additions, deletions, or an unparsed diff.

CRAP on this change: 24 functions, worst 14.7, 0 at or above 30 (0.0%), crap load 0.0. The workspace scores 167 functions, worst 14.7, 0 at or above 30, crap load 0.0. Formula in the report: `CRAP = complexity^2 * (1 - coverage)^3 + complexity`. This is line coverage, not basis-path coverage. The aggregate LCOV percentage is not a CRAP score. The threshold used for "at or above" is 30. CRAP does not by itself fail the run.

Selection rule, from `test/cherry-test/src/crap.rs` `change_on_added_lines`: a function is included when its path matches a changed file and its span overlaps an added line.

Highest scores on the change:

- `fmt` 14.7, complexity 12, 73%, `shop-rs-api/src/domain/model.rs:26`
- `from` 13.3, complexity 12, 79%, `shop-rs-api/src/infrastructure/web.rs:164`
- `every_domain_error_has_an_http_status` 6.0, complexity 2, 0%, `shop-rs-api/src/infrastructure/web.rs:553`
- `discountedUnitCents` 4.4, complexity 4, 71%, `shop-ts-lib-cart/src/cart.ts:42`
- `parse` 4.1, complexity 4, 80%, `shop-rs-api/src/domain/model.rs:113`

Diff row for `DiscountPercent::parse` in `shop-rs-api/src/domain/model.rs`: lines 113–115 and 118–121 are added and covered. Line 116 is added and uncovered: `_ => Err(DomainError::InvalidDiscount)`. Line 117, the closing brace of the match, is added and has no coverage class.

Diff rails, from `test/cherry-test-common/src/report_shell.rs`: left rail is the test outcome (green pass, red fail). Right rail is coverage (blue reached, amber gap). `++` is added and `--` is removed. Covered code background is `#dbeafe` in light mode. Uncovered code background is amber. An added or removed line colors the gutter, not the code background, except where coverage also paints the code.

Repositories: this run is one root, `app/shop`, one git repository. Each configuration root has its own run and report. A second repository is a second `--root`. A workspace file can name several projects, and each project can set its own `diff_base`. Do not write that this shop run combined several git repositories into one diff.

## Do not claim

- That the price-mismatch failure is caused by the discount math. The listed price was 1800 and the test expected 1500.
- That every changed line in the TypeScript or React packages is uncovered. The React package's changed files in the coverage columns are 100% covered.
- That CRAP above a number failed the job. The header is FAIL because one test failed.
- That `discountedUnitCents` is uncovered. Its coverage on this change is 71%. The amber gap is the uncovered part of that function, which the landing page already names as the rejection of a percent outside 0 through 100.

## Images

Regenerate the screenshots from `site/demo/change/change-request.html` if the report changes. Do not draw a fake diff.

- `site/articles/assets/cover.jpg` is the article and LinkedIn cover. It is the supplied illustration: a magnifying glass over change notes labeled Rust, TypeScript and React. The marks on those notes are a drawing, not the shop source. Do not describe them as `DiscountPercent::parse`.
- `site/articles/assets/cover.html`, `cover-light.png` and `cover-dark.png` are the earlier isometric diagram. The article no longer uses them.
- `site/articles/assets/diff-light.png` and `diff-dark.png` — `DiscountPercent::parse`, lines 113–121.
- `site/articles/assets/tests-light.png` and `tests-dark.png` — Tests on this change, 13 rows.
- `site/articles/assets/crap-light.png` and `crap-dark.png` — On this change, 24 function rows.

## Article

# Single change request, multiple technologies

When I review a change request, I want to understand whether the feature has been tested properly across all the components it touches. I also want to see whether the change has introduced code that is becoming too complex or difficult to test.

This gets harder when the feature involves several repositories or monorepos, different technologies and separate test tools. The information is there, but collecting it and connecting it to the change takes time.

This is one of the reasons I am building Cherry Test. It runs the configured tests and brings together their results, coverage, code complexity and CRAP scores. The change view puts this information next to the code being reviewed, across the components involved.

![Cover](assets/cover.jpg)

## A small feature can touch several parts of the application

Suppose we need to add a product discount to an online shop.

The API needs to return the discount and validate its value. The cart library needs to calculate the discounted price. The frontend needs to display it, and the checkout needs to save the correct total.

In a larger application, these components may belong to different teams and live in different repositories. They can also use different languages and testing tools. But from the customer’s point of view, they are all part of the same feature.

For this example, I added a 10% discount to a mug in the demo shop. Its original price is 1800 cents. With the discount, each mug costs 1620 cents, so two mugs should cost 3240.

The implementation touches 19 files across a Rust API, a TypeScript cart library and a React frontend. The demo keeps these components in one repository, which makes the example easier to explore.

I ran Cherry Test on the `discount` branch and compared it with `main`.

## Reviewing the change across components

The report shows the code that changed, with the test result and the coverage for each line.

A useful example is the new `DiscountPercent::parse` function in the Rust API. It accepts a valid discount percentage and returns an error for an invalid one.

In the diff, the line that accepts the percentage is blue: a test reached it. The line that returns `InvalidDiscount` is amber: no test reached it.

This gives me something concrete to investigate. The valid discount path was exercised, but the rejection path still needs a test.

The report puts this information directly beside the code changes. Added lines have a `++` mark, removed lines have `--`, and the old and new line numbers are shown together.

The rail on the left shows the recorded test outcome for the line. The rail on the right shows coverage: blue for reached lines, amber for missed lines.

I can follow the same change into the other components. The TypeScript function `discountedUnitCents` has 71% coverage. The measured lines in the changed React files are fully covered. These numbers help me decide where to look more closely; I still need to read what the tests actually check.

## The checkout passed, but the run failed

The test `applies_the_mug_discount` passed. It checks the behaviour this change is supposed to introduce: 1620 cents per mug and 3240 cents for two.

There is also a failing Rust test, `reports_a_price_mismatch`. It expects a listed price of 1500 cents, while the actual price is 1800. This is the failure behind the report's FAIL status, and it needs to be investigated during the review.

Both appear in **Tests on this change**, which contains 13 tests: 12 passed and a single test failed.

This section selects recorded tests whose source files are in the diff. It does not list every test that executed changed code. That distinction matters when reading the report: it helps me review the tests included in the change, while coverage shows which lines were reached.

## Deciding where to look more closely

The report also combines complexity and coverage through the CRAP score. I use this as another indication of where a function may need attention.

The **On this change** section includes functions that overlap added lines. There are 24 in this example, across Rust, TypeScript and React. The highest score is 14.7, below the report's high-score threshold of 30.

The score does not fail the run. Here, the failing price assertion is what makes the run fail.

What I find useful is having these details together while reviewing the change request. I can follow the discount through the API, cart and shop, inspect the checkout test, see the failure and find the uncovered validation path.

This is what I am working towards with Cherry Test: making it easier to understand how a complete feature was tested across the components it touches.

You can explore the example in [the pull request](https://github.com/lmrrcc/shop/pull/1) and find the project at [cherry-test.com](https://cherry-test.com/).

## LinkedIn post

Use `site/articles/assets/cover.jpg` as the post image. The title on the image is the post title. The marks under the magnifying glass are an illustration, not the shop source.

Single change request, multiple technologies.

When I review a change request, I want to understand whether the feature has been tested properly across all the components it touches. I also want to see whether the change has introduced code that is becoming too complex or difficult to test.

This gets harder when the feature involves several repositories or monorepos, different technologies and separate test tools. The information is there, but collecting it and connecting it to the change takes time.

This is one of the reasons I am building Cherry Test. It runs the configured tests and brings together their results, coverage, code complexity and CRAP scores. The change view puts this information next to the code being reviewed.

For this example, I added a 10% discount to a mug in the demo shop. Its original price is 1800 cents. With the discount, each mug costs 1620 cents, so two mugs should cost 3240.

The implementation touches 19 files across a Rust API, a TypeScript cart library and a React frontend. The demo keeps these components in one repository. I ran Cherry Test on the `discount` branch and compared it with `main`.

Report: https://cherry-test.com/demo/change/change-request.html
Article: https://cherry-test.com/articles/change-request.html
Pull request: https://github.com/lmrrcc/shop/pull/1
