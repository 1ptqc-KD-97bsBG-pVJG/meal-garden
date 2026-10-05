Recorded October 4, 2026, using plain HTTP; tests do not access the network.

- `usda.json`: FoodData Central branded search, `query=Nancy plain nonfat yogurt&dataType=Branded`. Metadata and up to five foods retained. https://api.nal.usda.gov/fdc/v1/foods/search
- `off.json`: Open Food Facts product `0030000012000`, fields `product_name,brands,code,nutriments,serving_size`. The search endpoint returned HTTP 503 during capture; the product endpoint supplies a real normalization fixture. https://world.openfoodfacts.org/api/v2/product/0030000012000

Synthetic variations in tests exercise ambiguity, missing fields, liquid units and failures; they are not claims about these products.
