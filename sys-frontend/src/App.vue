<script setup lang="ts"></script>

<template>
  <RouterView v-slot="{ Component }">
    <KeepAlive include="DataAnalysis">
      <component :is="Component" />
    </KeepAlive>
  </RouterView>
</template>

<style scoped></style>
