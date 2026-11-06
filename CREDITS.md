# Credits: third-party data

## HYG Database v4.1 (star catalogue)

| | |
|---|---|
| What | `sky/src/main/resources/com/truesim/effects/atmosphere/sky/stars/hyg-v41-mag7.csv`: 15,598 stars of magnitude ≤ 7.0 |
| Source | <https://github.com/astronexus/HYG-Database> (the author's official repository), `hyg/CURRENT/hygdata_v41.csv` |
| Downloaded from | `https://raw.githubusercontent.com/astronexus/HYG-Database/3bf37f4b2d5460e1278286320d1d62fab9b493c1/hyg/CURRENT/hygdata_v41.csv` |
| Version | HYG v4.1, commit `3bf37f4b2d5460e1278286320d1d62fab9b493c1` (2024-08-17) |
| SHA-256 of the download | `d9f69fd86bbf90a4e4d52b4c5c53eacfa6dfc0bfdef85bfd94f095e0bebe4ebd` (33,932,548 bytes) |
| SHA-256 of its licence file | `f404190403d31e0ce7223f4cb7af954485ad88077358330754dc5c892a856627` (`hyg/CURRENT/LICENSE`, same commit) |
| SHA-256 of the bundled subset | `dad79b0f3a9d51314ed0d987140d366900ebc443f4e904a68a923bd9fc798ee8` |
| Licence | Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0), verified from the repository's `LICENSE` and `hyg/README.md` |
| Attribution | HYG Database by David Nash (astronexus), compiled from Hipparcos, the Yale Bright Star Catalog and Gliese |
| Changes | magnitude ≤ 7.0, Sun removed, 12 columns kept, values unchanged (`tools/hyg_subset.py`) |
| Licence and notice in the jar | `stars/LICENSE-HYG.md`, `stars/LICENSE-HYG-original.md` next to the data |

To rebuild the subset: download the file above into an empty folder, check its SHA-256, then
`python -I tools/hyg_subset.py <hygdata_v41.csv> sky/src/main/resources/com/truesim/effects/atmosphere/sky/stars/hyg-v41-mag7.csv 7.0`.

## Algorithms (published, implemented from the text; no code copied)

- J. Meeus, *Astronomical Algorithms*, 2nd ed., Willmann-Bell 1998: chapters 7, 12, 13, 16, 21, 22, 23, 25, 40, 47, 48.
- F. Espenak, J. Meeus, *Five Millennium Canon of Solar Eclipses*, NASA/TP-2006-214141: ΔT polynomials.
- I. Reda, A. Andreas, *Solar Position Algorithm for Solar Radiation Applications*, NREL/TP-560-34302: test values.
- ESA, *The Hipparcos and Tycho Catalogues*, SP-1200, vol. 1 §1.5.3: the galactic rotation matrix.
- F. J. Ballesteros, *New insights into black bodies*, EPL 97 (2012) 34008: temperature from B−V.
- C. Wyman, P.-P. Sloan, P. Shirley, *Simple Analytic Approximations to the CIE XYZ Color Matching Functions*,
  JCGT 2(2), 2013: the CIE 1931 fit used for star colours.
- K. Krisciunas, B. E. Schaefer, *A model of the brightness of moonlight*, PASP 103 (1991); C. W. Allen,
  *Astrophysical Quantities*: the moon's and the stars' photometry, airglow brightness.
