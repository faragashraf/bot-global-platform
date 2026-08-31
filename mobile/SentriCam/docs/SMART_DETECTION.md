# Smart Detection privacy foundation

Smart Detection V1 contains contracts only. It does not run classifiers, create identity templates,
store face images, or collect biometric data.

Future known-person recognition must be local-first by default. Raw face images must not be stored
unless a separately reviewed capability explicitly requires them. Identity embeddings/templates must
be encrypted, upload requires explicit consent, deleting an identity must remove all derived data,
and the application must visibly indicate whenever recognition is active.

Smart Detection remains a capability boundary rather than a transport feature. No classifier,
model delivery, or Smart Detection runtime is added by the monorepo refactor.
