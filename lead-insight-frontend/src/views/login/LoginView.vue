<script setup lang="ts">
/**
 * 登录页 /login —— 左右分栏（左 40% 品牌区 / 右 60% 表单区），无营销 Hero。
 * 错误提示就地展示后端返回的 message（后端故意不区分「用户不存在/密码错误」，AC-03），
 * 因此登录接口以 silent 调用，不在拦截器重复弹窗。
 */
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import { Lock, User } from '@element-plus/icons-vue';
import { useUserStore } from '@/stores/user';
import { homePathFor } from '@/router/guard';
import { toBizMessage } from '@/api/error';

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();

const formRef = ref<FormInstance>();
const submitting = ref(false);
const errorText = ref('');

const form = reactive({ username: '', password: '' });

const rules: FormRules<typeof form> = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' },
  ],
};

async function onSubmit(): Promise<void> {
  if (!formRef.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;

  submitting.value = true;
  errorText.value = '';
  try {
    await userStore.login({ username: form.username.trim(), password: form.password });
    const redirect = route.query.redirect;
    const target =
      typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('/login')
        ? redirect
        : homePathFor(userStore.role);
    ElMessage.success('登录成功');
    await router.replace(target);
  } catch (err) {
    errorText.value = toBizMessage(err).message;
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="login">
    <section class="login__brand">
      <div class="login__brand-inner">
        <div class="login__logo" aria-hidden="true">LI</div>
        <h1 class="login__brand-title">销售线索智能识别系统</h1>
        <p class="login__brand-sub">把客户往来邮件变成可排序、可解释、可留痕的线索清单</p>
        <div class="login__sample">
          <p class="login__sample-label">一条真实业务示例</p>
          <p class="login__sample-text">
            客户：广州鑫茂五金制品有限公司 · 想了解 304 不锈钢管件的报价，需要 500 米，月底前要货。
          </p>
        </div>
      </div>
    </section>

    <section class="login__form-panel">
      <div class="login__form-wrap">
        <h2 class="login__form-title">欢迎回来</h2>
        <p class="login__form-sub">登录后进入线索工作台</p>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="onSubmit"
        >
          <el-form-item label="账号" prop="username">
            <el-input v-model="form.username" placeholder="请输入账号" autocomplete="username">
              <template #prefix>
                <el-icon :size="16"><User /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              show-password
              autocomplete="current-password"
            >
              <template #prefix>
                <el-icon :size="16"><Lock /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <p v-if="errorText" class="login__error">{{ errorText }}</p>

          <el-button
            class="login__submit"
            type="primary"
            native-type="submit"
            :loading="submitting"
          >
            登录
          </el-button>
        </el-form>

      </div>
    </section>
  </div>
</template>

<style scoped>
.login {
  display: grid;
  grid-template-columns: 40% 60%;
  height: 100%;
  background: var(--surface);
}

.login__brand {
  background: var(--primary-subtle);
  border-right: 1px solid var(--border);
}

.login__brand-inner {
  max-width: 360px;
  padding: 20vh var(--space-10) 0;
}

.login__logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-md);
  background: var(--primary);
  color: var(--primary-on);
  font-size: var(--text-base);
  font-weight: var(--fw-semibold);
}

.login__brand-title {
  margin-top: var(--space-4);
  font-size: var(--text-2xl);
  font-weight: var(--fw-semibold);
  letter-spacing: var(--tracking-tight);
  color: var(--fg);
}

.login__brand-sub {
  margin-top: var(--space-2);
  font-size: var(--text-base);
  color: var(--fg-2);
  line-height: var(--leading-relaxed);
}

.login__sample {
  margin-top: var(--space-8);
  padding-top: var(--space-4);
  border-top: 1px solid var(--primary-border);
}

.login__sample-label {
  font-size: var(--text-xs);
  font-weight: var(--fw-medium);
  letter-spacing: var(--tracking-wide);
  color: var(--primary);
}

.login__sample-text {
  margin-top: var(--space-2);
  font-size: var(--text-sm);
  color: var(--fg-2);
  line-height: var(--leading-relaxed);
}

.login__form-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-6);
}

.login__form-wrap {
  width: 360px;
}

.login__form-title {
  font-size: var(--text-2xl);
  font-weight: var(--fw-semibold);
  letter-spacing: var(--tracking-tight);
  color: var(--fg);
}

.login__form-sub {
  margin-top: var(--space-1);
  margin-bottom: var(--space-6);
  font-size: var(--text-base);
  color: var(--fg-3);
}

.login__error {
  margin-bottom: var(--space-3);
  font-size: var(--text-base);
  color: var(--danger-text);
}

.login__submit {
  width: 100%;
  height: 40px;
}
</style>
