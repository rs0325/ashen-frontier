# 画像生成の最終プロンプト

全て組込み `image_gen.imagegen` を使用。API/CLIは使用していない。英語は画像生成へ渡した原文として保存する。説明・採用範囲の正本は日本語のdocs/design/16-m2-ruins.md。

## 建材見本：materials-concept.png

```text
Use case: stylized-concept. Asset type: Ashen Frontier proposal material reference sheet, not finished game textures. Create one clean wide catalog sheet with eight horizontal rows and six columns of Minecraft-like voxel material samples. Neutral warm gray background, consistent orthographic isometric cubes, muted realistic weathered palette, crisp low resolution pixel textures, no bloom, no photoreal microdetails. Row labels in English: REINFORCED CONCRETE; PLAIN CONCRETE; BRICK; ASPHALT; STRUCTURAL STEEL; GLASS; TIMBER; SHEET STEEL. Column labels: INTACT, WEATHERED, CRACKED, SPALLED / LOSS, SEVERE LOSS, RUBBLE. RC row shows gray intact concrete, grime, cracks, then chipped concrete with visibly embedded rusty rebar, then partial concrete with corroded interrupted rebar and missing sections, then rubble mixed with rebar. Plain concrete and brick must never contain rebar. Asphalt shows oxidized gray then cracks then missing patches and loose aggregate. Steel shows coating loss, orange rust, pitting, reduced section and broken ends, scrap. Glass row uses pane frames, grime then cracks then broken panes then empty frame, shards. Timber gray weathering then splits then rotten missing sections and fragments, no rebar or metal rust. Sheet steel peeling coating then rust perforations and torn remnants. Empty/missing matter should be visible silhouette rather than merely drawn black cracks. Every sample independent, same scale. No people, no gameplay UI. Top title MATERIAL WEATHERING — CONCEPT. Footer: Visual states, not a universal physical timeline.
```

## 外観見本：ruins-exteriors.png

```text
Use case: stylized-concept. Asset type: Ashen Frontier proposal exterior reference sheet. One wide image with four equal panels, voxel architecture rendered with restrained 16 pixel style material textures, orthographic isometric three-quarter view, realistic construction details but Minecraft block scale. Quiet abandoned Earth roughly 150 years after collapse of a late 23rd century industrial civilization; no fantasy, no futuristic glowing devices. Panel A: two-storey small reinforced-concrete apartment with one side collapsed, exposed corroded rebar in concrete broken edges, missing floor on one side, an intact dry ground-level walkaround, irregular rubble at wall foot, sparse shrubs. Panel B: inland old brick farm storehouse and low timber outbuilding, roof partly lost, intact brick and weathered wood mixed, sparse dry grasses, no obligatory food or loot. Panel C: compact steel-frame industrial warehouse on dry bank, torn corroded sheet cladding, asymmetric roof failure, partial collapsed truss resting in rubble, short wall segments and no long vista. Panel D: small northern cold research support hut on rocky tundra, severe concrete spalling and frost-weathered surface, patchy snow, missing glass, no demolition of glacier, no terrain alteration. A through D should show different local damage patterns, not uniform noise deletion. Keep landforms organic, no circular pads, no straight cut cliff, no grid settlement, no river crossings, no floating debris or falling-block physics, no people or monsters, no crates or loot, no tall tower. Short labels A URBAN, B FARM, C INDUSTRY, D COLD. Top title RUINS — EXTERIOR CONCEPTS. Footer CONCEPT ONLY — NOT IN-GAME CAPTURES. No dimensions or invented map coordinates.
```

## 説明地図：distribution-gpt.png

参照は承認時のD4実列背景・D5制御点・候補窓の縮小図（D4実列背景＋D5制御点＋今回の候補窓）。初版は農地模様と提案外設備が混入したため不採用とし、以下の生成・修正を経た最終版だけを保存。

```text
Use case: infographic-diagram. Asset type: Ashen Frontier M2 proposal explanatory MAP. Input image 1 is a reference map for geography and relative placement, not an edit target. Generate a readable illustrated overhead map of the northwest continent only, north up, ocean on west and southeast, irregular long north-south mountainous western spine and branched interior ridges, northern snow, a major east-side river flowing south toward the southeast coast. Preserve the overall geography and relative positions visible in the reference, simplify it tastefully; do not create a new continent. Mark seven sparse localized ruin clusters, each with tiny voxel building silhouettes and a short colored number: 1 CENTRAL URBAN (two clusters near F1); 2 EASTERN FARMS (two clusters by dry terraces west of big river); 3 NORTHERN / WESTERN MINES (two separated mining support settlements, not ore mines); 4 SOUTHWEST DRYLAND (two small sparse maintenance clusters); 5 SOUTHEAST INLAND INDUSTRY (two clusters); 6 SOUTHEAST COASTAL LOGISTICS (two small dry-land clusters, no new artificial island); 7 FAR NORTH COLD STATION SUPPORT (two very sparse huts). Empty wilderness should dominate. Do not fill the continent with city buildings. Broken paths are faint curving explanatory links only, no straight highways, no settlement grid, no circles, no radial streets, no artificial flat plateaus. Do not move the river or mountains to accommodate ruins. Colors muted earth green, ash gray, rust red, cold blue. Clear compact legend, title RUIN DISTRIBUTION — PROPOSAL. Footer SCHEMATIC — EXACT COORDINATES IN COMPANION MAP. No invented numerical coordinate ticks or scale. This image conveys distribution intent, not verified positions or final terrain. Restrained hand-painted cartographic illustration with voxel ruin vignettes.
```

修正プロンプト：

```text
undefined
```

この地図は概略図。座標・縮尺・棟数はdocs/design/ruins-distribution.jsonとdocs/design/images/ruins-distribution-exact.pngを参照する。
